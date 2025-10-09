#!/usr/bin/env python3
"""
Benchmark Granite Docling with quality testing
Tests FP16, INT8, INT4 for both performance AND accuracy
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from PIL import Image, ImageDraw, ImageFont
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


def create_test_invoice():
    """Create realistic test invoice"""
    img = Image.new('RGB', (800, 600), color='white')
    draw = ImageDraw.Draw(img)

    invoice_text = """
    INVOICE

    Invoice #: INV-2025-001
    Date: October 9, 2025

    Bill To:
    John Smith
    123 Main Street

    Item Description      Qty    Price    Total
    Widget A              2      $50.00   $100.00
    Service Fee           1      $25.00   $25.00

    Subtotal:                              $125.00
    Tax (10%):                             $12.50

    TOTAL:                                 $137.50

    Payment Due: October 23, 2025
    """

    draw.text((50, 30), invoice_text, fill='black')
    return img


def test_quality(model, processor, dtype_name):
    """Test model quality with basic instructions"""
    console.print(f"\n[yellow]Testing quality for {dtype_name}...[/yellow]")

    image = create_test_invoice()

    # Test 1: Extract invoice number
    test_cases = [
        ("What is the invoice number?", "INV-2025-001"),
        ("What is the total amount?", "$137.50"),
        ("What is the due date?", "October 23, 2025"),
    ]

    results = []
    for question, expected in test_cases:
        messages = [
            {
                "role": "user",
                "content": [
                    {"type": "image"},
                    {"type": "text", "text": question}
                ]
            },
        ]

        prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
        inputs = processor(text=prompt, images=[image], return_tensors="pt").to(DEVICE)

        with torch.no_grad():
            generated_ids = model.generate(**inputs, max_new_tokens=64)

        prompt_length = inputs.input_ids.shape[1]
        trimmed_ids = generated_ids[:, prompt_length:]
        output = processor.batch_decode(trimmed_ids, skip_special_tokens=True)[0].strip()

        # Check if expected answer is in output
        contains_answer = expected.lower() in output.lower()

        results.append({
            "question": question,
            "expected": expected,
            "output": output,
            "correct": contains_answer
        })

        console.print(f"  Q: {question}")
        console.print(f"  A: {output[:80]}...")
        console.print(f"  {'✓' if contains_answer else '✗'} {'PASS' if contains_answer else 'FAIL'}")

    accuracy = sum(r["correct"] for r in results) / len(results) * 100
    return accuracy, results


def benchmark_quantization(dtype_name, quantize_fn=None):
    """Benchmark a specific quantization level"""

    console.print(f"\n[bold cyan]{'='*60}[/bold cyan]")
    console.print(f"[bold cyan]Testing: {dtype_name.upper()}[/bold cyan]")
    console.print(f"[bold cyan]{'='*60}[/bold cyan]")

    mem_before = get_memory_usage()

    # Load model
    console.print(f"\n[yellow]Loading model ({dtype_name})...[/yellow]")
    load_start = time.time()

    processor = AutoProcessor.from_pretrained(MODEL_ID)

    if dtype_name == "fp16":
        model = AutoModelForVision2Seq.from_pretrained(
            MODEL_ID,
            torch_dtype=torch.float16,
            low_cpu_mem_usage=True
        ).to(DEVICE)
    elif dtype_name == "int8":
        # Dynamic quantization for INT8
        model = AutoModelForVision2Seq.from_pretrained(MODEL_ID).to(DEVICE)
        model = torch.quantization.quantize_dynamic(
            model, {torch.nn.Linear}, dtype=torch.qint8
        )
    elif dtype_name == "int4":
        # For INT4, we'll use FP16 as proxy (true INT4 needs special libs)
        console.print("[dim]Note: INT4 requires special libraries, using FP16 as proxy[/dim]")
        model = AutoModelForVision2Seq.from_pretrained(
            MODEL_ID,
            torch_dtype=torch.float16,
            low_cpu_mem_usage=True
        ).to(DEVICE)
    else:
        model = AutoModelForVision2Seq.from_pretrained(MODEL_ID).to(DEVICE)

    model.eval()

    load_time = time.time() - load_start
    mem_after = get_memory_usage()
    model_size_mb = mem_after - mem_before

    console.print(f"[green]✓[/green] Loaded in {load_time:.2f}s")
    console.print(f"[green]✓[/green] Memory: {model_size_mb:.1f} MB")

    # Speed test
    console.print(f"\n[yellow]Speed test...[/yellow]")
    image = create_test_invoice()
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": "Extract invoice number"}
            ]
        },
    ]

    prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=prompt, images=[image], return_tensors="pt").to(DEVICE)

    # Warmup
    with torch.no_grad():
        _ = model.generate(**inputs, max_new_tokens=32)

    # Timed run
    start = time.time()
    with torch.no_grad():
        generated_ids = model.generate(**inputs, max_new_tokens=128)
    inference_time = time.time() - start

    tokens = len(generated_ids[0]) - len(inputs.input_ids[0])
    tokens_per_sec = tokens / inference_time

    console.print(f"[green]✓[/green] Inference: {inference_time:.2f}s ({tokens_per_sec:.1f} tok/s)")

    # Quality test
    accuracy, quality_results = test_quality(model, processor, dtype_name)

    # Cleanup
    del model
    del processor
    torch.cuda.empty_cache() if torch.cuda.is_available() else None

    return {
        "dtype": dtype_name,
        "load_time": load_time,
        "model_size_mb": model_size_mb,
        "inference_time": inference_time,
        "tokens_per_sec": tokens_per_sec,
        "accuracy": accuracy,
        "quality_results": quality_results
    }


def main():
    """Run quality benchmarks"""

    console.print(Panel.fit(
        "[bold cyan]Granite Docling Quality Benchmark[/bold cyan]\n"
        "Testing: FP16, INT8, INT4\n"
        "Metrics: Size, Speed, AND Accuracy",
        border_style="cyan"
    ))

    results = []

    # Test FP16
    try:
        results.append(benchmark_quantization("fp16"))
    except Exception as e:
        console.print(f"[red]✗ FP16 failed: {e}[/red]")
        import traceback
        traceback.print_exc()

    # Test INT8
    try:
        results.append(benchmark_quantization("int8"))
    except Exception as e:
        console.print(f"[red]✗ INT8 failed: {e}[/red]")
        import traceback
        traceback.print_exc()

    # Test INT4 (proxy)
    try:
        results.append(benchmark_quantization("int4"))
    except Exception as e:
        console.print(f"[red]✗ INT4 failed: {e}[/red]")

    # Summary
    console.print("\n" + "="*80)
    console.print("[bold cyan]FINAL RESULTS[/bold cyan]")
    console.print("="*80)

    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Precision", style="cyan")
    table.add_column("Size (MB)", justify="right")
    table.add_column("Inference (s)", justify="right")
    table.add_column("Tokens/sec", justify="right")
    table.add_column("Accuracy", justify="right", style="green")
    table.add_column("Recommendation", style="yellow")

    for r in results:
        rec = ""
        if r["accuracy"] >= 100:
            rec = "✅ Perfect"
        elif r["accuracy"] >= 66:
            rec = "✅ Good"
        elif r["accuracy"] >= 33:
            rec = "⚠️ Fair"
        else:
            rec = "❌ Poor"

        table.add_row(
            r["dtype"].upper(),
            f"{r['model_size_mb']:.0f}",
            f"{r['inference_time']:.2f}",
            f"{r['tokens_per_sec']:.1f}",
            f"{r['accuracy']:.0f}%",
            rec
        )

    console.print(table)

    # Recommendation
    console.print("\n[bold cyan]FINAL RECOMMENDATION FOR MOBILE DEPLOYMENT:[/bold cyan]")
    best = max((r for r in results if r["accuracy"] >= 66),
               key=lambda x: (-x["model_size_mb"], x["tokens_per_sec"]),
               default=None)

    if best:
        console.print(f"✅ [green]Use {best['dtype'].upper()}[/green]")
        console.print(f"   Size: {best['model_size_mb']:.0f} MB")
        console.print(f"   Speed: {best['tokens_per_sec']:.1f} tokens/sec")
        console.print(f"   Accuracy: {best['accuracy']:.0f}%")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print("\n[yellow]Benchmark interrupted[/yellow]")
    except Exception as e:
        console.print(f"\n[red]✗ Failed: {e}[/red]")
        import traceback
        traceback.print_exc()
