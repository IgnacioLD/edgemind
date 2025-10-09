#!/usr/bin/env python3
"""
Export Phi-3-mini to ONNX for mobile NPU/GPU acceleration
Microsoft Phi-3 has MIT license (excellent for commercial use)
"""

import torch
from transformers import AutoTokenizer, AutoModelForCausalLM
from pathlib import Path
import json

def export_phi3_mini():
    """
    Export Phi-3-mini 3.8B to ONNX with INT8 quantization
    License: MIT (permissive, commercial-friendly)
    """

    print("=" * 70)
    print("🚀 Phi-3-mini 3.8B - Mobile ONNX Export")
    print("=" * 70)

    model_name = "microsoft/Phi-3-mini-4k-instruct"
    output_dir = Path("./models/phi3_mobile")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n📥 Model: {model_name}")
    print(f"💾 Output: {output_dir}")
    print(f"📜 License: MIT (Microsoft)")
    print(f"\n✅ Why Phi-3-mini:")
    print(f"   • Excellent quality (3.8B params)")
    print(f"   • MIT license (commercial use OK)")
    print(f"   • Optimized for mobile/edge")
    print(f"   • Strong instruction following")
    print(f"   • ~2GB INT8 quantized")

    # Step 1: Load tokenizer
    print(f"\n📦 Step 1: Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        model_name,
        trust_remote_code=True
    )
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
    print(f"   This will download ~7GB, may take a few minutes...")

    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        torch_dtype=torch.float16,
        low_cpu_mem_usage=True,
        device_map="cpu",
        trust_remote_code=True
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
        onnx_path = output_dir / "phi3_mini_mobile.onnx"

        print(f"   Exporting (this may take 5-10 minutes)...")

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

        # Step 4: Quantize to INT8
        print(f"\n⚡ Step 4: Quantizing to INT8 for NPU...")

        try:
            from onnxruntime.quantization import quantize_dynamic, QuantType

            quantized_path = output_dir / "phi3_mini_mobile_int8.onnx"

            print(f"   Applying INT8 quantization...")
            quantize_dynamic(
                str(onnx_path),
                str(quantized_path),
                weight_type=QuantType.QUInt8
            )

            quant_size_mb = quantized_path.stat().st_size / (1024**2)
            reduction = (1 - quant_size_mb/file_size_mb) * 100
            print(f"✅ Quantized model created")
            print(f"   Path: {quantized_path}")
            print(f"   Size: {quant_size_mb:.1f} MB ({reduction:.0f}% reduction)")
            print(f"   ⭐ This is the model to use on Android")

        except ImportError:
            print(f"⚠️  onnxruntime not installed, skipping quantization")
            print(f"   Using FP16 model (larger but still works)")

    except Exception as e:
        print(f"❌ Export failed: {e}")
        print(f"\n💡 Phi-3 may have export limitations.")
        print(f"   Alternative: Use optimum-cli for ONNX export")
        return False

    # Step 5: Create config
    print(f"\n📋 Step 5: Creating deployment config...")

    config = {
        "model_name": "Phi-3-mini-4k-instruct",
        "format": "onnx",
        "quantization": "int8",
        "license": "MIT",
        "params": "3.8B",
        "opset_version": 14,
        "vocab_size": len(vocab),
        "context_length": 4096,
        "acceleration_backends": ["nnapi", "gpu", "cpu"],
        "notes": {
            "license": "MIT - Commercial use allowed",
            "nnapi": "Automatic NPU/TPU detection on Android 9+",
            "performance": "Expected 10-20 tokens/sec on mid-range NPU",
            "quality": "Excellent instruction following and reasoning"
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
    if (output_dir / "phi3_mini_mobile_int8.onnx").exists():
        print(f"   {output_dir / 'phi3_mini_mobile_int8.onnx'} ⭐ USE THIS")
    else:
        print(f"   {onnx_path}")
    print(f"   {vocab_path}")
    print(f"   {config_path}")

    print(f"\n🚀 Android Integration:")
    print(f"   1. Copy INT8 model → android/app/src/main/assets/models/")
    print(f"   2. Copy vocab.json → android/app/src/main/assets/models/")
    print(f"   3. Update ModelRepositoryImpl:")
    print(f"      TEXT_MODEL_PATH = 'models/phi3_mini_mobile_int8.onnx'")
    print(f"   4. Build and test on device")

    print(f"\n📊 Expected Performance:")
    print(f"   • Size: ~2GB (INT8)")
    print(f"   • NPU Speed: 10-20 tokens/sec")
    print(f"   • Quality: Excellent (3.8B params)")
    print(f"   • License: MIT ✅")

    print(f"\n💡 Multi-model Setup:")
    print(f"   ✅ Phi-3-mini (this): General Q&A, reasoning")
    print(f"   ✅ Granite Docling: Document understanding")
    print(f"   Both have excellent licenses (MIT + Apache 2.0)")
    print(f"=" * 70)

    return True

if __name__ == "__main__":
    export_phi3_mini()
