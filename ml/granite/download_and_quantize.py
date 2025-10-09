#!/usr/bin/env python3
"""
Download Granite Docling model and save with INT4 quantization
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq, BitsAndBytesConfig
import os

def main():
    print("=" * 60)
    print("Granite Docling INT4 Download & Quantization")
    print("=" * 60)

    model_name = "ibm-granite/granite-docling-258m"
    output_dir = "./models/granite_int4"

    print(f"\n📥 Downloading model: {model_name}")
    print(f"💾 Output directory: {output_dir}")

    # Create output directory
    os.makedirs(output_dir, exist_ok=True)

    # Configure INT4 quantization (NF4)
    print("\n⚙️  Configuring INT4 (NF4) quantization...")
    quantization_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4"  # NormalFloat 4-bit
    )

    # Download processor (tokenizer + image processor)
    print("\n📦 Downloading processor...")
    processor = AutoProcessor.from_pretrained(
        model_name,
        trust_remote_code=True
    )

    # Save processor
    processor.save_pretrained(output_dir)
    print(f"✅ Processor saved to {output_dir}")

    # Download model with INT4 quantization
    print("\n🔥 Loading model with INT4 quantization...")
    print("⏳ This will take a few minutes...")

    model = AutoModelForVision2Seq.from_pretrained(
        model_name,
        quantization_config=quantization_config,
        device_map="auto",
        trust_remote_code=True,
        torch_dtype=torch.float16
    )

    print(f"\n✅ Model loaded successfully!")
    print(f"📊 Model type: {type(model).__name__}")
    print(f"💾 Memory footprint: INT4 quantized (~358 MB)")

    # Save quantized model
    print(f"\n💾 Saving quantized model to {output_dir}...")
    model.save_pretrained(output_dir)

    print("\n" + "=" * 60)
    print("✅ SUCCESS!")
    print("=" * 60)
    print(f"\n📁 Model saved to: {output_dir}")
    print(f"📦 Files:")
    print(f"   - Processor (tokenizer + image processor)")
    print(f"   - Model weights (INT4 quantized)")
    print("\n💡 Next steps:")
    print("   1. Export to ONNX format")
    print("   2. Convert to mobile-optimized format")
    print("   3. Add to Android project")
    print("=" * 60)

if __name__ == "__main__":
    main()
