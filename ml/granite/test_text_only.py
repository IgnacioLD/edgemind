#!/usr/bin/env python3
"""
Test Granite Docling with TEXT-ONLY queries (no images)
Quick test to see if it can handle general Q&A
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq, BitsAndBytesConfig
from PIL import Image
from rich.console import Console
from rich.panel import Panel
from rich.table import Table
import time

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cpu"


def load_model_int4():
    """Load INT4 quantized model"""
    console.print("\n[yellow]Loading Granite Docling INT4...[/yellow]")

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
    model.eval()

    console.print("[green]✓[/green] Model loaded (358 MB)")
    return processor, model


def test_text_query(processor, model, question, expected_answer=None):
    """Test with text-only query (still need dummy image for vision model)"""

    # Create minimal dummy image (required by vision model)
    dummy_img = Image.new('RGB', (100, 100), color='white')

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
    inputs = processor(text=prompt, images=[dummy_img], return_tensors="pt").to(DEVICE)

    # Generate with minimal tokens (CPU is slow)
    console.print(f"  [dim]Generating (max 32 tokens)...[/dim]")
    start = time.time()

    with torch.no_grad():
        generated_ids = model.generate(**inputs, max_new_tokens=32)

    latency = time.time() - start

    prompt_length = inputs.input_ids.shape[1]
    trimmed_ids = generated_ids[:, prompt_length:]
    output = processor.batch_decode(trimmed_ids, skip_special_tokens=True)[0].strip()

    # Check answer
    passed = False
    if expected_answer:
        passed = expected_answer.lower() in output.lower()

    return {
        "question": question,
        "output": output,
        "latency": latency,
        "expected": expected_answer,
        "passed": passed
    }


def main():
    """Run text-only tests"""

    console.print(Panel.fit(
        "[bold cyan]Granite Docling - Text-Only Q&A Test[/bold cyan]\n"
        "Testing if Granite can handle general questions\n"
        "(Note: Vision model, testing text capability)",
        border_style="cyan"
    ))

    # Load model
    processor, model = load_model_int4()

    # Test cases - general Q&A (non-document)
    test_cases = [
        {"q": "What is 2 + 2?", "a": "4"},
        {"q": "What is the capital of France?", "a": "Paris"},
        {"q": "How many days in a week?", "a": "7"},
        {"q": "What color is the sky?", "a": "blue"},
        {"q": "What is 10% of 100?", "a": "10"},
    ]

    console.print(f"\n[bold cyan]Running {len(test_cases)} text-only tests...[/bold cyan]")
    console.print("[yellow]⚠️ CPU inference is slow, this will take time[/yellow]\n")

    results = []

    for i, test in enumerate(test_cases, 1):
        console.print(f"\n[bold]Test {i}/{len(test_cases)}:[/bold]")
        console.print(f"  Q: {test['q']}")

        try:
            result = test_text_query(processor, model, test['q'], test['a'])
            results.append(result)

            status = "✓ PASS" if result['passed'] else "✗ FAIL"
            console.print(f"  A: {result['output']}")
            console.print(f"  {status} (Expected: {test['a']}, {result['latency']:.1f}s)")

        except KeyboardInterrupt:
            console.print("\n[yellow]Test interrupted[/yellow]")
            break
        except Exception as e:
            console.print(f"  [red]✗ ERROR: {e}[/red]")

    # Summary
    if results:
        console.print("\n" + "="*60)
        console.print("[bold cyan]RESULTS SUMMARY[/bold cyan]")
        console.print("="*60 + "\n")

        passed = sum(1 for r in results if r['passed'])
        total = len(results)
        avg_latency = sum(r['latency'] for r in results) / total

        table = Table(show_header=True)
        table.add_column("Question")
        table.add_column("Expected")
        table.add_column("Got")
        table.add_column("Pass")

        for r in results:
            table.add_row(
                r['question'][:30] + "...",
                r['expected'] or "N/A",
                r['output'][:30] + "...",
                "✓" if r['passed'] else "✗"
            )

        console.print(table)

        console.print(f"\n[bold]Score:[/bold] {passed}/{total} ({passed/total*100:.0f}%)")
        console.print(f"[bold]Avg Latency:[/bold] {avg_latency:.1f}s per query")

        # Assessment
        console.print("\n[bold cyan]ASSESSMENT:[/bold cyan]")
        if passed / total >= 0.7:
            console.print("✅ [green]Granite handles general Q&A well[/green]")
            console.print("   → Can use as single model (simpler)")
        else:
            console.print("⚠️ [yellow]Granite struggles with general Q&A[/yellow]")
            console.print("   → Need dedicated general LLM (Phi-3 or Llama)")

        console.print("\n[dim]Note: Latency will be much faster on device with NPU[/dim]")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print("\n[yellow]Test interrupted by user[/yellow]")
    except Exception as e:
        console.print(f"\n[red]✗ Test failed: {e}[/red]")
        import traceback
        traceback.print_exc()
