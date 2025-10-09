#!/usr/bin/env python3
"""
Export a simple text model to ONNX for testing the mobile pipeline
This uses a lightweight model that definitely works with ONNX export

Once pipeline is verified, can upgrade to larger models
"""

import torch
from transformers import AutoTokenizer, AutoModelForCausalLM
from pathlib import Path
import json

def export_simple_model():
    """
    Export a simple, proven model for testing mobile acceleration
    Using TinyLlama 1.1B - small, fast, works great on mobile
    """

    print("=" * 70)
    print("🚀 TinyLlama 1.1B - Mobile ONNX Export (Test Model)")
    print("=" * 70)

    model_name = "TinyLlama/TinyLlama-1.1B-Chat-v1.0"
    output_dir = Path("./models/tinyllama_mobile")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n📥 Model: {model_name}")
    print(f"💾 Output: {output_dir}")
    print(f"\n💡 Why TinyLlama for testing:")
    print(f"   • Small (1.1B params, ~2GB)")
    print(f"   • Fast inference on mobile")
    print(f"   • Proven ONNX export support")
    print(f"   • Great for testing NPU/GPU pipeline")
    print(f"   • Can upgrade to Granite/Phi-3 later")

    # Step 1: Load tokenizer
    print(f"\n📦 Step 1: Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    tokenizer.save_pretrained(output_dir)

    # Save vocab as JSON for Android
    vocab = tokenizer.get_vocab()
    vocab_path = output_dir / "vocab.json"
    with open(vocab_path, 'w') as f:
        json.dump(vocab, f)

    print(f"✅ Tokenizer saved")
    print(f"   Vocabulary size: {len(vocab)}")

    # Step 2: Load model
    print(f"\n🔥 Step 2: Loading model (FP16)...")
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.float16,
        low_cpu_mem_usage=True,
        device_map="cpu"
    )
    model.eval()
    print(f"✅ Model loaded")

    # Step 3: Export to ONNX
    print(f"\n🔄 Step 3: Exporting to ONNX...")
    print(f"   Creating sample inputs...")

    # Sample input
    sample_text = "Hello, how are you?"
    inputs = tokenizer(sample_text, return_tensors="pt")
    input_ids = inputs['input_ids']
    attention_mask = inputs['attention_mask']

    print(f"   Input shape: {input_ids.shape}")

    try:
        onnx_path = output_dir / "tinyllama_mobile.onnx"

        print(f"   Exporting (this may take 2-3 minutes)...")

        torch.onnx.export(
            model,
            (input_ids, attention_mask),
            str(onnx_path),
            input_names=['input_ids', 'attention_mask'],
            output_names=['logits'],
            dynamic_axes={
                'input_ids': {0: 'batch', 1: 'sequence'},
                'attention_mask': {0: 'batch', 1: 'sequence'},
                'logits': {0: 'batch', 1: 'sequence'}
            },
            opset_version=14,
            do_constant_folding=True,
            verbose=False
        )

        file_size_mb = onnx_path.stat().st_size / (1024**2)
        print(f"✅ ONNX export successful!")
        print(f"   Path: {onnx_path}")
        print(f"   Size: {file_size_mb:.1f} MB")

        # Step 4: Optimize ONNX
        print(f"\n⚡ Step 4: Optimizing ONNX for mobile...")

        try:
            import onnx
            from onnxruntime.quantization import quantize_dynamic, QuantType

            # Dynamic quantization to INT8 (best NPU support)
            quantized_path = output_dir / "tinyllama_mobile_int8.onnx"

            print(f"   Applying INT8 quantization...")
            quantize_dynamic(
                str(onnx_path),
                str(quantized_path),
                weight_type=QuantType.QUInt8
            )

            quant_size_mb = quantized_path.stat().st_size / (1024**2)
            print(f"✅ Quantized model created")
            print(f"   Path: {quantized_path}")
            print(f"   Size: {quant_size_mb:.1f} MB ({quant_size_mb/file_size_mb*100:.0f}% of original)")

        except ImportError:
            print(f"⚠️  onnxruntime not installed, skipping quantization")

    except Exception as e:
        print(f"❌ Export failed: {e}")
        return False

    # Step 5: Create config
    print(f"\n📋 Step 5: Creating deployment config...")

    config = {
        "model_name": "TinyLlama-1.1B",
        "format": "onnx",
        "quantization": "int8",
        "opset_version": 14,
        "vocab_size": len(vocab),
        "acceleration_backends": ["nnapi", "gpu", "cpu"],
        "notes": {
            "purpose": "Test model for verifying NPU/GPU acceleration pipeline",
            "upgrade_path": "Replace with Granite Docling or Phi-3-mini after testing",
            "nnapi": "Automatic NPU/TPU detection on Android 9+",
            "performance": "Expected 10-20 tokens/sec on mid-range devices with NPU"
        }
    }

    config_path = output_dir / "deployment_config.json"
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)

    print(f"✅ Config saved")

    # Summary
    print(f"\n" + "=" * 70)
    print(f"✅ EXPORT COMPLETE!")
    print(f"=" * 70)
    print(f"\n📁 Output files:")
    print(f"   {onnx_path}")
    if (output_dir / "tinyllama_mobile_int8.onnx").exists():
        print(f"   {output_dir / 'tinyllama_mobile_int8.onnx'} (RECOMMENDED)")
    print(f"   {vocab_path}")
    print(f"   {config_path}")

    print(f"\n🚀 Next steps:")
    print(f"   1. Copy INT8 model to Android: assets/models/")
    print(f"   2. Copy vocab.json to Android: assets/models/")
    print(f"   3. Update ModelRepositoryImpl model path")
    print(f"   4. Build and test on device")

    print(f"\n📊 Testing Strategy:")
    print(f"   ✅ Test with TinyLlama first (verify NPU/GPU works)")
    print(f"   ✅ Check hardware acceleration in logs")
    print(f"   ✅ Benchmark performance")
    print(f"   ➡️  Then upgrade to Granite/Phi-3 when pipeline proven")

    print(f"\n💡 Why this approach:")
    print(f"   • TinyLlama definitely exports to ONNX (proven)")
    print(f"   • Tests entire acceleration pipeline")
    print(f"   • Faster iteration (smaller download/export)")
    print(f"   • Can demo working NPU acceleration today")
    print(f"=" * 70)

    return True

if __name__ == "__main__":
    export_simple_model()
