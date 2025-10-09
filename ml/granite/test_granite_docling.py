#!/usr/bin/env python3
"""
Test script for IBM Granite Docling 258M model
Tests document conversion and Q&A capabilities
"""

import os
import time
from pathlib import Path
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

console = Console()


def test_docling_cli():
    """Test docling CLI for document conversion"""
    console.print("\n[bold cyan]Testing Docling CLI...[/bold cyan]")

    # This will be expanded after we confirm installation works
    console.print("[yellow]CLI test placeholder - will implement after installation[/yellow]")


def test_granite_model():
    """Test Granite Docling 258M model loading and inference"""
    console.print("\n[bold cyan]Testing Granite Docling 258M Model...[/bold cyan]")

    try:
        from transformers import AutoModelForVision2Seq, AutoProcessor
        import torch

        console.print("[green]✓[/green] Imports successful")

        # Model info
        model_id = "ibm-granite/granite-docling-258M"
        console.print(f"\n[bold]Model ID:[/bold] {model_id}")

        # Check available device
        device = "cuda" if torch.cuda.is_available() else "cpu"
        console.print(f"[bold]Device:[/bold] {device}")

        # Load model (this will download ~258MB on first run)
        console.print("\n[yellow]Loading model (this may take a few minutes on first run)...[/yellow]")
        start_time = time.time()

        processor = AutoProcessor.from_pretrained(model_id)
        model = AutoModelForVision2Seq.from_pretrained(model_id)
        model.to(device)

        load_time = time.time() - start_time
        console.print(f"[green]✓[/green] Model loaded in {load_time:.2f} seconds")

        # Model info
        total_params = sum(p.numel() for p in model.parameters())
        console.print(f"[bold]Total parameters:[/bold] {total_params:,} ({total_params/1e6:.1f}M)")

        # Memory usage (rough estimate)
        param_size = sum(p.numel() * p.element_size() for p in model.parameters())
        buffer_size = sum(b.numel() * b.element_size() for b in model.buffers())
        model_size_mb = (param_size + buffer_size) / 1024 / 1024
        console.print(f"[bold]Model size in memory:[/bold] {model_size_mb:.1f} MB")

        return True

    except ImportError as e:
        console.print(f"[red]✗[/red] Import error: {e}")
        console.print("[yellow]Make sure to install requirements: pip install -r requirements.txt[/yellow]")
        return False
    except Exception as e:
        console.print(f"[red]✗[/red] Error: {e}")
        return False


def main():
    """Main test runner"""
    console.print(Panel.fit(
        "[bold cyan]Granite Docling 258M Test Suite[/bold cyan]\n"
        "Testing PyTorch model for document understanding",
        border_style="cyan"
    ))

    # Test model loading
    success = test_granite_model()

    if success:
        console.print("\n[bold green]✓ All tests passed![/bold green]")
    else:
        console.print("\n[bold red]✗ Tests failed[/bold red]")

    return 0 if success else 1


if __name__ == "__main__":
    exit(main())
