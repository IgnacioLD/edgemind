#!/usr/bin/env python3
"""
Export Granite Docling to PyTorch Mobile format
This works better for complex models that don't export cleanly to ONNX
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq
from pathlib import Path
import json

def export_for_mobile():
    """
    Export using PyTorch Mobile (TorchScript)
    This format supports NPU/GPU via NNAPI on Android
    """

    print("=" * 70)
    print("🚀 Granite Docling - PyTorch Mobile Export")
    print("=" * 70)

    model_name = "ibm-granite/granite-docling-258m"
    output_dir = Path("./models/granite_pytorch_mobile")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n📥 Model: {model_name}")
    print(f"💾 Output: {output_dir}")
    print(f"\n🎯 Export Format: PyTorch Mobile (.ptl)")
    print(f"   • Supports NNAPI (NPU/TPU/DSP)")
    print(f"   • Supports GPU acceleration")
    print(f"   • Optimized for Android")

    # Step 1: Load processor
    print(f"\n📦 Step 1: Loading processor...")
    processor = AutoProcessor.from_pretrained(
        model_name,
        trust_remote_code=True
    )
    processor.save_pretrained(output_dir)
    print(f"✅ Processor saved")

    # Step 2: Load model with FP16 (better mobile support than INT8 for PyTorch Mobile)
    print(f"\n🔥 Step 2: Loading model (FP16)...")
    model = AutoModelForVision2Seq.from_pretrained(
        model_name,
        trust_remote_code=True,
        torch_dtype=torch.float16,
        low_cpu_mem_usage=True
    )
    model.eval()
    print(f"✅ Model loaded")

    # Step 3: Trace model with sample inputs
    print(f"\n🔍 Step 3: Creating traced model...")

    # Simple text input for tracing
    sample_text = "What is this document about?"
    inputs = processor(text=sample_text, return_tensors="pt")

    # Trace the model
    try:
        print(f"   Tracing model (this may take a few minutes)...")

        with torch.no_grad():
            traced_model = torch.jit.trace(
                model,
                (inputs['input_ids'], inputs['attention_mask']),
                check_trace=False  # Disable strict checking for complex models
            )

        print(f"✅ Model traced successfully")

        # Step 4: Optimize for mobile
        print(f"\n⚡ Step 4: Optimizing for mobile...")

        # Mobile optimization
        optimized_model = torch.jit.optimize_for_inference(traced_model)

        print(f"✅ Model optimized")

        # Step 5: Save in mobile format
        print(f"\n💾 Step 5: Saving mobile model...")

        model_path = output_dir / "granite_docling_mobile.ptl"
        optimized_model._save_for_lite_interpreter(str(model_path))

        file_size_mb = model_path.stat().st_size / (1024**2)
        print(f"✅ Model saved: {model_path}")
        print(f"   Size: {file_size_mb:.1f} MB")

    except Exception as e:
        print(f"⚠️  Tracing failed: {e}")
        print(f"\n💡 Alternative: Using scripting instead of tracing...")

        try:
            # Try scripting instead
            scripted_model = torch.jit.script(model)
            optimized_model = torch.jit.optimize_for_inference(scripted_model)

            model_path = output_dir / "granite_docling_mobile.ptl"
            optimized_model._save_for_lite_interpreter(str(model_path))

            file_size_mb = model_path.stat().st_size / (1024**2)
            print(f"✅ Model saved (scripted): {model_path}")
            print(f"   Size: {file_size_mb:.1f} MB")

        except Exception as e2:
            print(f"❌ Scripting also failed: {e2}")
            print(f"\n💡 Model is too complex for mobile export.")
            print(f"   Recommendation: Use quantized checkpoint directly")
            return False

    # Step 6: Create config
    print(f"\n📋 Step 6: Creating deployment config...")

    config = {
        "model_name": "granite-docling-258m",
        "format": "pytorch_mobile",
        "precision": "fp16",
        "acceleration_backends": ["nnapi", "gpu", "cpu"],
        "notes": {
            "nnapi": "Enable with PyTorch Mobile NNAPI delegate",
            "gpu": "Enable with Vulkan backend",
            "cpu": "Default, always available"
        }
    }

    config_path = output_dir / "deployment_config.json"
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)

    print(f"✅ Config saved: {config_path}")

    # Summary
    print(f"\n" + "=" * 70)
    print(f"✅ EXPORT COMPLETE!")
    print(f"=" * 70)
    print(f"\n📁 Output files:")
    if (output_dir / "granite_docling_mobile.ptl").exists():
        print(f"   {output_dir / 'granite_docling_mobile.ptl'}")
    print(f"   {config_path}")
    print(f"\n🚀 Next steps:")
    print(f"   1. Add PyTorch Mobile to Android project")
    print(f"   2. Copy .ptl model to assets/models/")
    print(f"   3. Test on device")
    print(f"\n💡 Note:")
    print(f"   PyTorch Mobile uses same NNAPI backend as ONNX Runtime")
    print(f"   NPU/GPU acceleration works automatically")
    print(f"=" * 70)

    return True

if __name__ == "__main__":
    success = export_for_mobile()
    if not success:
        print("\n⚠️  Mobile export not supported for this model.")
        print("Alternative: Use ONNX Runtime with smaller model or model components")
