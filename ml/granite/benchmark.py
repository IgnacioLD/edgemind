#!/usr/bin/env python3
"""
Benchmark Granite Docling with different quantization levels
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from PIL import Image, ImageDraw
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
import time
import psutil
import os

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cpu"


def get_memory_usage():
    """Get current memory usage in MB"""
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / (1024 ** 2)


def create_test_image():
    """Create simple test document image"""
    img = Image.new('RGB', (800, 600), color='white')
    draw = ImageDraw.Draw(img)
    text = """INVOICE #12345
Date: 2025-10-09
Amount: $500.00"""
    draw.text((50, 50), text, fill='black')
    return img


def benchmark_model(dtype_str="fp32"):
    """Benchmark model with specific dtype"""

    console.print(f"\n[bold cyan]{'='*60}[/bold cyan]")
    console.print(f"[bold cyan]Benchmarking: {dtype_str.upper()}[/bold cyan]")
    console.print(f"[bold cyan]{'='*60}[/bold cyan]")

    # Map dtype
    dtype_map = {
        "fp32": torch.float32,
        "fp16": torch.float16,
        "bf16": torch.bfloat16,
    }
    dtype = dtype_map.get(dtype_str, torch.float32)

    # Memory before load
    mem_before = get_memory_usage()

    # Load model
    console.print(f"\n[yellow]Loading model ({dtype_str})...[/yellow]")
    load_start = time.time()

    processor = AutoProcessor.from_pretrained(MODEL_ID)
    model = AutoModelForVision2Seq.from_pretrained(
        MODEL_ID,
        torch_dtype=dtype,
        low_cpu_mem_usage=True
    ).to(DEVICE)
    model.eval()

    load_time = time.time() - load_start
    mem_after = get_memory_usage()
    model_size_mb = mem_after - mem_before

    console.print(f"[green]✓[/green] Loaded in {load_time:.2f}s")
    console.print(f"[green]✓[/green] Memory usage: {model_size_mb:.1f} MB")

    # Create test input
    image = create_test_image()
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": "Extract the invoice number."}
            ]
        },
    ]

    # Prepare inputs
    prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=prompt, images=[image], return_tensors="pt").to(DEVICE)

    # Warm-up run
    console.print("\n[yellow]Warm-up inference...[/yellow]")
    with torch.no_grad():
        _ = model.generate(**inputs, max_new_tokens=50)
    console.print("[green]✓[/green] Warm-up complete")

    # Benchmark inference (3 runs)
    console.print("\n[yellow]Running benchmark (3 iterations)...[/yellow]")
    inference_times = []

    for i in range(3):
        torch.cuda.empty_cache() if torch.cuda.is_available() else None

        start = time.time()
        with torch.no_grad():
            generated_ids = model.generate(**inputs, max_new_tokens=128)
        inference_time = time.time() - start

        inference_times.append(inference_time)
        console.print(f"  Run {i+1}: {inference_time:.2f}s")

    avg_inference = sum(inference_times) / len(inference_times)

    # Decode output
    prompt_length = inputs.input_ids.shape[1]
    trimmed_ids = generated_ids[:, prompt_length:]
    output = processor.batch_decode(trimmed_ids, skip_special_tokens=True)[0]

    tokens_generated = len(trimmed_ids[0])
    tokens_per_sec = tokens_generated / avg_inference

    console.print(f"\n[green]✓[/green] Avg inference: {avg_inference:.2f}s")
    console.print(f"[green]✓[/green] Tokens/sec: {tokens_per_sec:.1f}")
    console.print(f"[green]✓[/green] Output: {output[:100]}...")

    # Clean up
    del model
    del processor
    torch.cuda.empty_cache() if torch.cuda.is_available() else None

    return {
        "dtype": dtype_str,
        "load_time": load_time,
        "model_size_mb": model_size_mb,
        "avg_inference_time": avg_inference,
        "tokens_per_sec": tokens_per_sec,
        "tokens_generated": tokens_generated
    }


def main():
    """Run benchmarks"""

    console.print(Panel.fit(
        "[bold cyan]Granite Docling 258M Benchmark Suite[/bold cyan]\n"
        f"Device: {DEVICE.upper()}\n"
        "Testing: FP16 (balanced, mobile-friendly)",
        border_style="cyan"
    ))

    results = []

    # Skip FP32 - too slow on laptop CPU
    console.print("\n[dim]Skipping FP32 (too slow on CPU, not mobile-friendly)[/dim]")

    # Test FP16 (balanced - RECOMMENDED for mobile)
    try:
        results.append(benchmark_model("fp16"))
    except Exception as e:
        console.print(f"[red]✗ FP16 failed: {e}[/red]")

    # Summary table
    console.print("\n" + "="*80)
    console.print("[bold cyan]BENCHMARK RESULTS SUMMARY[/bold cyan]")
    console.print("="*80)

    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Precision", style="cyan")
    table.add_column("Size (MB)", justify="right")
    table.add_column("Load Time (s)", justify="right")
    table.add_column("Inference (s)", justify="right")
    table.add_column("Tokens/sec", justify="right")
    table.add_column("vs FP32 (~1000MB)", justify="right")

    for r in results:
        # FP32 would be ~1000MB (from previous test)
        size_reduction = f"-{(1 - r['model_size_mb']/1000)*100:.0f}%"
        table.add_row(
            r["dtype"].upper(),
            f"{r['model_size_mb']:.1f}",
            f"{r['load_time']:.2f}",
            f"{r['avg_inference_time']:.2f}",
            f"{r['tokens_per_sec']:.1f}",
            size_reduction
        )

    console.print(table)

    # Recommendations
    console.print("\n[bold cyan]RECOMMENDATIONS:[/bold cyan]")
    if any(r["dtype"] == "fp16" for r in results):
        fp16 = next(r for r in results if r["dtype"] == "fp16")
        console.print(f"✅ [green]FP16 reduces size by ~50% ({fp16['model_size_mb']:.0f} MB)[/green]")
        console.print(f"✅ [green]FP16 tokens/sec: {fp16['tokens_per_sec']:.1f}[/green]")
        console.print(f"📱 [yellow]For modern Android devices: FP16 is RECOMMENDED for balance[/yellow]")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print("\n[yellow]Benchmark interrupted[/yellow]")
    except Exception as e:
        console.print(f"\n[red]✗ Benchmark failed: {e}[/red]")
        import traceback
        traceback.print_exc()
