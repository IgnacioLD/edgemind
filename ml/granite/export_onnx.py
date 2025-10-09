#!/usr/bin/env python3
"""
Export Granite Docling 258M to ONNX format
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from rich.console import Console
from rich.panel import Panel
import time
import os

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cpu"
OUTPUT_DIR = "granite/onnx_models"


def export_to_onnx():
    """Export model to ONNX format"""

    console.print(Panel.fit(
        "[bold cyan]Granite Docling ONNX Export[/bold cyan]",
        border_style="cyan"
    ))

    # Create output directory
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    # Load model
    console.print("\n[yellow]Loading PyTorch model...[/yellow]")
    start = time.time()

    processor = AutoProcessor.from_pretrained(MODEL_ID)
    model = AutoModelForVision2Seq.from_pretrained(MODEL_ID).to(DEVICE)
    model.eval()

    console.print(f"[green]✓[/green] Model loaded in {time.time() - start:.2f}s")

    # Create dummy inputs for export
    console.print("\n[yellow]Creating dummy inputs for export...[/yellow]")

    from PIL import Image
    dummy_image = Image.new('RGB', (224, 224), color='white')

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": "Convert this page to docling."}
            ]
        },
    ]

    prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=prompt, images=[dummy_image], return_tensors="pt").to(DEVICE)

    console.print(f"[green]✓[/green] Dummy inputs created")
    console.print(f"  Input IDs shape: {inputs.input_ids.shape}")
    console.print(f"  Pixel values shape: {inputs.pixel_values.shape}")

    # Export to ONNX
    console.print("\n[bold yellow]Exporting to ONNX...[/bold yellow]")
    console.print("[dim]This may take several minutes...[/dim]")

    output_path = os.path.join(OUTPUT_DIR, "granite_docling_258m.onnx")

    start = time.time()

    try:
        torch.onnx.export(
            model,
            (inputs.input_ids, inputs.pixel_values),
            output_path,
            export_params=True,
            opset_version=17,
            do_constant_folding=True,
            input_names=['input_ids', 'pixel_values'],
            output_names=['output'],
            dynamic_axes={
                'input_ids': {0: 'batch', 1: 'sequence'},
                'pixel_values': {0: 'batch'},
                'output': {0: 'batch', 1: 'sequence'}
            }
        )

        export_time = time.time() - start
        file_size = os.path.getsize(output_path) / (1024 ** 2)

        console.print(f"\n[green]✓[/green] Export completed in {export_time:.2f}s")
        console.print(f"[green]✓[/green] ONNX model saved to: {output_path}")
        console.print(f"[green]✓[/green] Model size: {file_size:.1f} MB")

        return output_path

    except Exception as e:
        console.print(f"\n[red]✗[/red] Export failed: {e}")
        console.print("\n[yellow]Note: Vision models are complex to export.[/yellow]")
        console.print("[yellow]Alternative: Use optimum library or export text-only components[/yellow]")
        raise


if __name__ == "__main__":
    try:
        export_to_onnx()
        console.print("\n[bold green]✓ ONNX export successful![/bold green]")
    except Exception as e:
        console.print(f"\n[bold red]✗ Export failed[/bold red]")
        import traceback
        traceback.print_exc()
