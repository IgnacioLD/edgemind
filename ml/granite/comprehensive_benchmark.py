#!/usr/bin/env python3
"""
Comprehensive benchmark suite for Granite Docling 258M INT4
Tests across 5 categories to determine if single model is sufficient
or if multi-model architecture is needed
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq, BitsAndBytesConfig
from PIL import Image, ImageDraw, ImageFont
from rich.console import Console
from rich.table import Table
from rich.panel import Panel
from rich.progress import Progress
import time

console = Console()

MODEL_ID = "ibm-granite/granite-docling-258M"
DEVICE = "cpu"


def create_invoice_image():
    """Create invoice test image"""
    img = Image.new('RGB', (800, 600), color='white')
    draw = ImageDraw.Draw(img)

    text = """INVOICE #INV-2025-001

Bill To: John Smith
Date: October 9, 2025

Item                  Qty    Price    Total
Widget A              2      $50.00   $100.00
Service Fee           1      $25.00   $25.00

Subtotal:                              $125.00
Tax (10%):                             $12.50
TOTAL:                                 $137.50

Payment Due: October 23, 2025"""

    draw.text((50, 50), text, fill='black')
    return img


def create_scene_image():
    """Create simple scene for vision testing"""
    img = Image.new('RGB', (600, 400), color='lightblue')
    draw = ImageDraw.Draw(img)

    # Draw simple scene
    draw.rectangle([100, 300, 500, 350], fill='brown')  # Ground
    draw.ellipse([250, 100, 350, 200], fill='yellow')   # Sun
    draw.polygon([(200, 300), (250, 200), (300, 300)], fill='green')  # Tree

    return img


def load_model():
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

    console.print("[green]✓[/green] Model loaded")
    return processor, model


def test_case(processor, model, image, question, expected_keywords, category):
    """Run single test case"""
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

    # Generate with minimal tokens (CPU is slow)
    start = time.time()
    with torch.no_grad():
        generated_ids = model.generate(**inputs, max_new_tokens=32)
    latency = time.time() - start

    prompt_length = inputs.input_ids.shape[1]
    trimmed_ids = generated_ids[:, prompt_length:]
    output = processor.batch_decode(trimmed_ids, skip_special_tokens=True)[0].strip()

    # Check if expected keywords found
    output_lower = output.lower()
    matches = sum(1 for kw in expected_keywords if kw.lower() in output_lower)
    score = (matches / len(expected_keywords)) * 100 if expected_keywords else 0

    return {
        "category": category,
        "question": question,
        "expected": expected_keywords,
        "output": output,
        "score": score,
        "latency": latency,
        "passed": score >= 50  # Pass if 50%+ keywords found
    }


def run_benchmark():
    """Run comprehensive benchmark"""

    console.print(Panel.fit(
        "[bold cyan]Granite Docling Comprehensive Benchmark[/bold cyan]\n"
        "Testing: Document, Q&A, Vision, Math, Conversation\n"
        "Goal: Determine if single model is sufficient",
        border_style="cyan"
    ))

    # Load model
    processor, model = load_model()

    # Create test images
    invoice = create_invoice_image()
    scene = create_scene_image()

    # Test suite
    test_suite = [
        # Category 1: Document Processing (Expected to excel)
        {
            "image": invoice,
            "question": "What is the invoice number?",
            "expected": ["INV-2025-001"],
            "category": "Document"
        },
        {
            "image": invoice,
            "question": "What is the total amount?",
            "expected": ["137.50", "$137.50", "137"],
            "category": "Document"
        },
        {
            "image": invoice,
            "question": "When is payment due?",
            "expected": ["October 23", "Oct 23", "23"],
            "category": "Document"
        },

        # Category 2: General Q&A (Unknown capability)
        {
            "image": invoice,  # Still need image
            "question": "What is 25% of 80?",
            "expected": ["20", "twenty"],
            "category": "General Q&A"
        },
        {
            "image": invoice,
            "question": "How many days in a week?",
            "expected": ["7", "seven"],
            "category": "General Q&A"
        },
        {
            "image": invoice,
            "question": "What is the capital of France?",
            "expected": ["Paris"],
            "category": "General Q&A"
        },

        # Category 3: Vision/Scene Understanding
        {
            "image": scene,
            "question": "What colors do you see in this image?",
            "expected": ["blue", "yellow", "green", "brown"],
            "category": "Vision"
        },
        {
            "image": scene,
            "question": "Describe what you see",
            "expected": ["sky", "sun", "tree", "ground"],
            "category": "Vision"
        },

        # Category 4: Math/Reasoning
        {
            "image": invoice,
            "question": "If the tax is 10%, what is the subtotal?",
            "expected": ["125", "$125"],
            "category": "Math"
        },
        {
            "image": invoice,
            "question": "How many items are listed?",
            "expected": ["2", "two"],
            "category": "Math"
        },
    ]

    # Run tests
    results = []

    console.print(f"\n[bold cyan]Running {len(test_suite)} test cases...[/bold cyan]")
    console.print("[dim]Note: Inference is slow on CPU, fast on NPU[/dim]\n")

    with Progress() as progress:
        task = progress.add_task("[cyan]Testing...", total=len(test_suite))

        for i, test in enumerate(test_suite, 1):
            console.print(f"\n[bold]Test {i}/{len(test_suite)}:[/bold] {test['category']}")
            console.print(f"  Q: {test['question']}")

            result = test_case(
                processor, model,
                test['image'],
                test['question'],
                test['expected'],
                test['category']
            )

            results.append(result)

            status = "✓ PASS" if result['passed'] else "✗ FAIL"
            console.print(f"  A: {result['output'][:60]}...")
            console.print(f"  {status} (Score: {result['score']:.0f}%, {result['latency']:.1f}s)")

            progress.update(task, advance=1)

    return results


def analyze_results(results):
    """Analyze benchmark results and make recommendation"""

    console.print("\n" + "="*80)
    console.print("[bold cyan]BENCHMARK RESULTS ANALYSIS[/bold cyan]")
    console.print("="*80 + "\n")

    # Group by category
    categories = {}
    for r in results:
        cat = r['category']
        if cat not in categories:
            categories[cat] = []
        categories[cat].append(r)

    # Category summary table
    table = Table(show_header=True, header_style="bold magenta")
    table.add_column("Category", style="cyan")
    table.add_column("Tests", justify="right")
    table.add_column("Passed", justify="right")
    table.add_column("Avg Score", justify="right")
    table.add_column("Avg Latency", justify="right")
    table.add_column("Assessment", style="yellow")

    for cat, cat_results in categories.items():
        total = len(cat_results)
        passed = sum(1 for r in cat_results if r['passed'])
        avg_score = sum(r['score'] for r in cat_results) / total
        avg_latency = sum(r['latency'] for r in cat_results) / total

        # Assessment
        if avg_score >= 70:
            assessment = "✅ Excellent"
        elif avg_score >= 50:
            assessment = "⚠️ Fair"
        else:
            assessment = "❌ Poor"

        table.add_row(
            cat,
            str(total),
            f"{passed}/{total}",
            f"{avg_score:.0f}%",
            f"{avg_latency:.1f}s",
            assessment
        )

    console.print(table)

    # Overall results
    total_tests = len(results)
    total_passed = sum(1 for r in results if r['passed'])
    overall_score = sum(r['score'] for r in results) / total_tests

    console.print(f"\n[bold]Overall Results:[/bold]")
    console.print(f"  Total Tests: {total_tests}")
    console.print(f"  Passed: {total_passed}/{total_tests} ({total_passed/total_tests*100:.0f}%)")
    console.print(f"  Average Score: {overall_score:.1f}%")

    # Decision recommendation
    console.print("\n" + "="*80)
    console.print("[bold cyan]ARCHITECTURE RECOMMENDATION[/bold cyan]")
    console.print("="*80 + "\n")

    # Analyze weak categories
    weak_categories = [
        cat for cat, cat_results in categories.items()
        if sum(r['score'] for r in cat_results) / len(cat_results) < 50
    ]

    if not weak_categories:
        console.print("[bold green]✅ SINGLE MODEL ARCHITECTURE[/bold green]")
        console.print("\nGranite Docling performs well across all categories.")
        console.print("Recommendation: Use Granite as single model for all tasks.\n")
        console.print("[green]Benefits:[/green]")
        console.print("  • Simpler architecture")
        console.print("  • Lower storage (358 MB only)")
        console.print("  • Faster app startup")
        console.print("  • Less complexity")
    else:
        console.print("[bold yellow]⚠️ MULTI-MODEL ARCHITECTURE RECOMMENDED[/bold yellow]")
        console.print(f"\nWeak categories: {', '.join(weak_categories)}")
        console.print("\nRecommendation: Add specialist model for weak areas.\n")
        console.print("[yellow]Architecture:[/yellow]")
        console.print("  • Granite Docling (358 MB) → Document, Vision tasks")
        console.print("  • Phi-3-mini/Llama 3.2 1B (~600-2000 MB) → General Q&A, Math")
        console.print("  • Intent router to coordinate models")
        console.print("\n[yellow]Benefits:[/yellow]")
        console.print("  • Better quality across all use cases")
        console.print("  • Specialist models excel at their tasks")
        console.print("\n[yellow]Trade-offs:[/yellow]")
        console.print("  • More storage (1-2.5 GB total)")
        console.print("  • More complex architecture")
        console.print("  • Need intent routing logic")

    # Next steps
    console.print("\n[bold cyan]NEXT STEPS:[/bold cyan]")
    if not weak_categories:
        console.print("  1. Export Granite INT4 to ONNX")
        console.print("  2. Convert ONNX to TFLite")
        console.print("  3. Begin Android integration")
    else:
        console.print("  1. Select general LLM (Phi-3-mini or Llama 3.2 1B)")
        console.print("  2. Quantize general LLM to INT4")
        console.print("  3. Benchmark general LLM")
        console.print("  4. Export both models to TFLite")
        console.print("  5. Design intent routing logic")


def main():
    """Main benchmark runner"""
    try:
        results = run_benchmark()
        analyze_results(results)

        console.print("\n[bold green]✓ Benchmark complete![/bold green]")
        console.print("\n[dim]Note: Latency will be much faster on device with NPU[/dim]")

    except KeyboardInterrupt:
        console.print("\n[yellow]Benchmark interrupted[/yellow]")
    except Exception as e:
        console.print(f"\n[red]✗ Benchmark failed: {e}[/red]")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    main()
