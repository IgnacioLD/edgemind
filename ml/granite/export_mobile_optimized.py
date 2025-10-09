#!/usr/bin/env python3
"""
Export Granite Docling to mobile-optimized ONNX format
Optimized for NPU/TPU/GPU acceleration on Android devices
"""

import torch
from transformers import AutoProcessor, AutoModelForVision2Seq, BitsAndBytesConfig
import os
from pathlib import Path

def export_for_mobile():
    """
    Export strategy for maximum hardware acceleration:

    1. Export to ONNX (universal format)
    2. Use dynamic quantization (INT8) - better NPU/TPU support than INT4
    3. Optimize for mobile inference (fused ops, reduced precision)

    Supported acceleration backends on Android:
    - NNAPI (NPU/TPU/DSP on Snapdragon, MediaTek, Exynos)
    - GPU Delegate (OpenGL/Vulkan)
    - Hexagon DSP
    - CPU fallback
    """

    print("=" * 70)
    print("🚀 Granite Docling - Mobile NPU/TPU/GPU Optimization")
    print("=" * 70)

    model_name = "ibm-granite/granite-docling-258m"
    output_dir = Path("./models/granite_mobile")
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n📥 Model: {model_name}")
    print(f"💾 Output: {output_dir}")
    print(f"\n🎯 Target Hardware:")
    print(f"   • Snapdragon NPU (NNAPI)")
    print(f"   • MediaTek APU (NNAPI)")
    print(f"   • Exynos NPU (NNAPI)")
    print(f"   • GPU (OpenCL/Vulkan)")
    print(f"   • Hexagon DSP")

    # Step 1: Load model with optimizations
    print(f"\n📦 Step 1: Loading model...")

    # Use INT8 quantization for better hardware support
    # INT8 has native NPU/TPU support on most devices
    quantization_config = BitsAndBytesConfig(
        load_in_8bit=True,  # INT8 instead of INT4 for better NPU support
    )

    try:
        model = AutoModelForVision2Seq.from_pretrained(
            model_name,
            quantization_config=quantization_config,
            device_map="auto",
            trust_remote_code=True,
            torch_dtype=torch.float16,
            low_cpu_mem_usage=True
        )

        processor = AutoProcessor.from_pretrained(
            model_name,
            trust_remote_code=True
        )

        print(f"✅ Model loaded with INT8 quantization")

    except Exception as e:
        print(f"⚠️  INT8 failed, falling back to FP16: {e}")
        # Fallback to FP16 for export
        model = AutoModelForVision2Seq.from_pretrained(
            model_name,
            device_map="auto",
            trust_remote_code=True,
            torch_dtype=torch.float16,
            low_cpu_mem_usage=True
        )
        processor = AutoProcessor.from_pretrained(
            model_name,
            trust_remote_code=True
        )
        print(f"✅ Model loaded with FP16")

    # Save processor for Android
    print(f"\n💾 Saving processor...")
    processor.save_pretrained(output_dir)

    # Step 2: Create sample inputs for tracing
    print(f"\n🔍 Step 2: Creating sample inputs for export...")

    # Text-only export (simpler, better compatibility)
    dummy_text = "What is this document about?"
    inputs = processor(text=dummy_text, return_tensors="pt")

    # Move to same device as model
    device = next(model.parameters()).device
    inputs = {k: v.to(device) for k, v in inputs.items()}

    print(f"✅ Sample inputs created")
    print(f"   Device: {device}")
    print(f"   Input shapes: {[(k, v.shape) for k, v in inputs.items()]}")

    # Step 3: Export to ONNX
    print(f"\n🔄 Step 3: Exporting to ONNX...")
    print(f"   This may take several minutes...")

    onnx_path = output_dir / "granite_docling_mobile.onnx"

    try:
        # Set model to eval mode
        model.eval()

        # Export with optimizations
        torch.onnx.export(
            model,
            tuple(inputs.values()),
            str(onnx_path),
            input_names=list(inputs.keys()),
            output_names=["logits"],
            dynamic_axes={
                key: {0: "batch_size", 1: "sequence_length"}
                for key in inputs.keys()
            },
            opset_version=14,  # ONNX opset 14 for better mobile support
            do_constant_folding=True,  # Optimize constant operations
            verbose=False
        )

        print(f"✅ ONNX export successful!")
        print(f"   Path: {onnx_path}")
        print(f"   Size: {onnx_path.stat().st_size / (1024**2):.1f} MB")

    except Exception as e:
        print(f"❌ ONNX export failed: {e}")
        print(f"\n💡 Note: Full model export may not be supported.")
        print(f"   Consider using PyTorch Mobile or using model in components")
        return

    # Step 4: Optimize ONNX for mobile
    print(f"\n⚡ Step 4: Optimizing ONNX for mobile inference...")

    try:
        import onnx
        from onnxruntime.transformers import optimizer

        # Load and optimize
        optimized_path = output_dir / "granite_docling_mobile_opt.onnx"

        opt_model = optimizer.optimize_model(
            str(onnx_path),
            model_type='bert',  # Use BERT-style optimizations
            num_heads=0,  # Auto-detect
            hidden_size=0,  # Auto-detect
        )

        opt_model.save_model_to_file(str(optimized_path))
        print(f"✅ Optimized ONNX created")
        print(f"   Path: {optimized_path}")
        print(f"   Size: {optimized_path.stat().st_size / (1024**2):.1f} MB")

    except ImportError:
        print(f"⚠️  onnxruntime not installed, skipping optimization")
        print(f"   Install with: pip install onnxruntime")
    except Exception as e:
        print(f"⚠️  Optimization failed: {e}")

    # Step 5: Export configuration
    print(f"\n📋 Step 5: Creating deployment configuration...")

    config = {
        "model_name": "granite-docling-258m",
        "quantization": "int8",
        "format": "onnx",
        "opset_version": 14,
        "acceleration_backends": [
            "nnapi",  # NPU/TPU via NNAPI
            "gpu",    # GPU via OpenCL/Vulkan
            "cpu"     # CPU fallback
        ],
        "input_names": list(inputs.keys()),
        "recommended_batch_size": 1,
        "max_sequence_length": 512,
        "notes": {
            "nnapi": "Best for NPU/TPU (Snapdragon, MediaTek, Exynos)",
            "gpu": "Best for GPU acceleration (Mali, Adreno)",
            "cpu": "Fallback for older devices"
        }
    }

    import json
    config_path = output_dir / "deployment_config.json"
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)

    print(f"✅ Configuration saved: {config_path}")

    # Summary
    print(f"\n" + "=" * 70)
    print(f"✅ EXPORT COMPLETE!")
    print(f"=" * 70)
    print(f"\n📁 Output files:")
    print(f"   {onnx_path}")
    if (output_dir / "granite_docling_mobile_opt.onnx").exists():
        print(f"   {output_dir / 'granite_docling_mobile_opt.onnx'}")
    print(f"   {config_path}")
    print(f"\n🚀 Next steps:")
    print(f"   1. Add ONNX Runtime Mobile to Android project")
    print(f"   2. Copy model to assets/models/")
    print(f"   3. Implement hardware detection")
    print(f"   4. Test on device with NPU/GPU acceleration")
    print(f"\n💡 Hardware Acceleration:")
    print(f"   • NNAPI will use NPU/TPU/DSP automatically")
    print(f"   • Falls back to GPU then CPU if NPU unavailable")
    print(f"   • INT8 quantization provides best NPU performance")
    print(f"=" * 70)

if __name__ == "__main__":
    export_for_mobile()
