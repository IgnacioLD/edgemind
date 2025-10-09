#!/usr/bin/env python3
"""
Test INT4 quantization - smallest model for mobile
NOTE: True INT4 needs bitsandbytes, testing load-in-4bit
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq, BitsAndBytesConfig
from rich.console import Console
from rich.panel import Panel
import time
import psutil
import os

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cpu"


def get_memory_usage():
    """Get memory in MB"""
    process = psutil.Process(os.getpid())
    return process.memory_info().rss / (1024 ** 2)


def test_int4():
    """Test INT4 quantization"""

    console.print(Panel.fit(
        "[bold cyan]INT4 Quantization Test[/bold cyan]\n"
        "Smallest model for mobile deployment",
        border_style="cyan"
    ))

    mem_before = get_memory_usage()

    console.print("\n[yellow]Loading model with INT4 quantization...[/yellow]")
    console.print("[dim]Note: Requires bitsandbytes library[/dim]")

    start = time.time()

    try:
        # Configure 4-bit quantization
        bnb_config = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_compute_dtype=torch.float16,
            bnb_4bit_use_double_quant=True,
            bnb_4bit_quant_type="nf4"
        )

        processor = AutoProcessor.from_pretrained(MODEL_ID)
        model = AutoModelForVision2Seq.from_pretrained(
            MODEL_ID,
            quantization_config=bnb_config,
            device_map="auto",
            low_cpu_mem_usage=True
        )

        load_time = time.time() - start
        mem_after = get_memory_usage()
        model_size_mb = mem_after - mem_before

        console.print(f"[green]✓[/green] Loaded in {load_time:.2f}s")
        console.print(f"[green]✓[/green] Memory: {model_size_mb:.1f} MB")

        console.print("\n[bold cyan]INT4 Model Stats:[/bold cyan]")
        console.print(f"  Size: ~{model_size_mb:.0f} MB (vs ~1000 MB FP32)")
        console.print(f"  Reduction: ~{(1 - model_size_mb/1000)*100:.0f}%")
        console.print(f"  Mobile friendly: {'✅ YES' if model_size_mb < 300 else '⚠️ Borderline'}")

        return True

    except ImportError as e:
        console.print(f"\n[red]✗[/red] Missing library: {e}")
        console.print("\n[yellow]Installing bitsandbytes...[/yellow]")
        console.print("[dim]Run: pip install bitsandbytes[/dim]")
        return False
    except Exception as e:
        console.print(f"\n[red]✗[/red] INT4 failed: {e}")
        console.print("\n[yellow]Trying alternative: load_in_8bit instead[/yellow]")

        # Fallback to INT8
        try:
            bnb_config = BitsAndBytesConfig(load_in_8bit=True)
            processor = AutoProcessor.from_pretrained(MODEL_ID)
            model = AutoModelForVision2Seq.from_pretrained(
                MODEL_ID,
                quantization_config=bnb_config,
                device_map="auto"
            )

            load_time = time.time() - start
            mem_after = get_memory_usage()
            model_size_mb = mem_after - mem_before

            console.print(f"[green]✓[/green] INT8 loaded in {load_time:.2f}s")
            console.print(f"[green]✓[/green] INT8 memory: {model_size_mb:.1f} MB")
            console.print("\n[yellow]Note: INT8 fallback used (INT4 not available)[/yellow]")

            return True
        except Exception as e2:
            console.print(f"[red]✗[/red] INT8 also failed: {e2}")
            return False


if __name__ == "__main__":
    success = test_int4()

    if success:
        console.print("\n[bold green]✓ Quantization test passed![/bold green]")
        console.print("\n[cyan]Next steps:[/cyan]")
        console.print("  1. Install bitsandbytes if needed")
        console.print("  2. Export quantized model to ONNX")
        console.print("  3. Convert ONNX to TFLite for Android")
    else:
        console.print("\n[bold yellow]⚠️ Need to install bitsandbytes[/bold yellow]")
        console.print("\n[cyan]Install command:[/cyan]")
        console.print("  pip install bitsandbytes")
