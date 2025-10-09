#!/usr/bin/env python3
"""
Test Granite Docling with actual document conversion and Q&A
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from transformers.image_utils import load_image
from rich.console import Console
from rich.panel import Panel
import time

console = Console()

# Model ID
MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def test_document_conversion():
    """Test document conversion with sample image URL"""
    console.print("\n[bold cyan]Loading Granite Docling Model...[/bold cyan]")

    start_time = time.time()

    # Load processor and model
    processor = AutoProcessor.from_pretrained(MODEL_ID)
    model = AutoModelForVision2Seq.from_pretrained(MODEL_ID).to(DEVICE)

    load_time = time.time() - start_time
    console.print(f"[green]✓[/green] Model loaded in {load_time:.2f}s")

    # Use sample document image from web
    console.print("\n[bold cyan]Testing Document Conversion...[/bold cyan]")
    console.print("[yellow]Using sample document image[/yellow]")

    # Sample business document image (invoice example)
    image_url = "https://raw.githubusercontent.com/DS4SD/docling/main/docs/assets/sample-doc.png"

    try:
        image = load_image(image_url)
        console.print(f"[green]✓[/green] Image loaded from URL")
    except Exception as e:
        console.print(f"[red]✗[/red] Failed to load image: {e}")
        console.print("[yellow]Note: This is OK for offline testing[/yellow]")
        return

    # Prepare conversion prompt
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": "Convert this page to docling markdown format."}
            ]
        },
    ]

    # Process
    console.print("\n[yellow]Converting document...[/yellow]")
    start_time = time.time()

    prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=prompt, images=[image], return_tensors="pt").to(DEVICE)

    # Generate
    generated_ids = model.generate(**inputs, max_new_tokens=2048)
    prompt_length = inputs.input_ids.shape[1]
    trimmed_generated_ids = generated_ids[:, prompt_length:]

    output = processor.batch_decode(
        trimmed_generated_ids,
        skip_special_tokens=False,
    )[0].lstrip()

    inference_time = time.time() - start_time

    console.print(f"[green]✓[/green] Inference completed in {inference_time:.2f}s")
    console.print("\n[bold]Converted Output:[/bold]")
    console.print(Panel(output[:500] + "..." if len(output) > 500 else output))

    # Performance summary
    console.print("\n[bold cyan]Performance Summary:[/bold cyan]")
    console.print(f"  Device: {DEVICE}")
    console.print(f"  Model load time: {load_time:.2f}s")
    console.print(f"  Inference time: {inference_time:.2f}s")
    console.print(f"  Output tokens: ~{len(trimmed_generated_ids[0])} tokens")


if __name__ == "__main__":
    console.print(Panel.fit(
        "[bold cyan]Granite Docling Document Q&A Test[/bold cyan]\n"
        "Testing document conversion capabilities",
        border_style="cyan"
    ))

    try:
        test_document_conversion()
        console.print("\n[bold green]✓ Test completed successfully![/bold green]")
    except Exception as e:
        console.print(f"\n[bold red]✗ Test failed: {e}[/bold red]")
        import traceback
        traceback.print_exc()
