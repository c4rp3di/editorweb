# FLUX pipeline status

**Integrated in the app:**
- manual-only package download;
- resumable HTTP downloads;
- coherent T2I graph list;
- Qwen2-style tokenizer and chat template;
- memory-mapped fp16 Qwen embedding lookup;
- expanded Qwen causal/padding mask;
- Qwen RoPE host tables;
- FLUX.2 4-axis RoPE host tables;
- FLUX.2 four-step empirical schedule;
- one-shot FP32 LiteRT GPU graph runner;
- 3 encoder taps → 7680-wide prompt embeddings;
- 4-step DiT graph chain;
- packed latent → VAE channel affine/unpatchify;
- local PNG result in the conversation UI.

**Runtime prerequisites that must be present in the downloaded coherent package:**
- projected 4×3072 timestep embeddings in `host/`;
- 128-channel VAE BatchNorm mean + std/variance in `host/`.

The app rejects generation rather than fabricating either learned artifact.
