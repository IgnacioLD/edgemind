#!/usr/bin/env python3
"""
Simple inference test with generated image
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from PIL import Image, ImageDraw, ImageFont
from rich.console import Console
from rich.panel import Panel
import time

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def create_sample_document():
    """Create a simple document image"""
    # Create white image with black text
    img = Image.new('RGB', (800, 600), color='white')
    draw = ImageDraw.Draw(img)

    # Add sample text
    text = """INVOICE #12345

Company Name: Test Corp
Date: 2025-10-09

Item             Qty    Price
Widget A          5     $10.00
Widget B          3     $15.00

Total: $95.00"""

    # Draw text (using default font)
    draw.text((50, 50), text, fill='black')

    return img


def test_inference():
    """Test model inference with simple image"""
    console.print("\n[bold cyan]Loading Model...[/bold cyan]")
    start = time.time()

    processor = AutoProcessor.from_pretrained(MODEL_ID)
    model = AutoModelForVision2Seq.from_pretrained(MODEL_ID).to(DEVICE)

    console.print(f"[green]✓[/green] Loaded in {time.time() - start:.2f}s")

    # Create sample image
    console.print("\n[bold cyan]Creating Sample Document...[/bold cyan]")
    image = create_sample_document()
    console.print("[green]✓[/green] Sample document created (800x600)")

    # Prepare prompt
    messages = [
        {
            "role": "user",
            "content": [
                {"type": "image"},
                {"type": "text", "text": "Extract the text from this invoice."}
            ]
        },
    ]

    console.print("\n[bold cyan]Running Inference...[/bold cyan]")
    start = time.time()

    prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
    inputs = processor(text=prompt, images=[image], return_tensors="pt").to(DEVICE)

    # Generate with limited tokens for speed
    generated_ids = model.generate(**inputs, max_new_tokens=512)
    prompt_length = inputs.input_ids.shape[1]
    trimmed_generated_ids = generated_ids[:, prompt_length:]

    output = processor.batch_decode(
        trimmed_generated_ids,
        skip_special_tokens=True,
    )[0].strip()

    inference_time = time.time() - start

    console.print(f"[green]✓[/green] Inference: {inference_time:.2f}s")
    console.print("\n[bold]Model Output:[/bold]")
    console.print(Panel(output if output else "[No output generated]"))

    # Stats
    console.print("\n[bold cyan]Performance Summary:[/bold cyan]")
    console.print(f"  Device: {DEVICE}")
    console.print(f"  Inference time: {inference_time:.2f}s")
    console.print(f"  Tokens per second: ~{len(trimmed_generated_ids[0]) / inference_time:.1f}")
    console.print(f"  Output tokens: {len(trimmed_generated_ids[0])}")


if __name__ == "__main__":
    console.print(Panel.fit(
        "[bold cyan]Granite Docling Simple Inference Test[/bold cyan]",
        border_style="cyan"
    ))

    try:
        test_inference()
        console.print("\n[bold green]✓ Test completed![/bold green]")
    except Exception as e:
        console.print(f"\n[bold red]✗ Test failed: {e}[/bold red]")
        import traceback
        traceback.print_exc()
