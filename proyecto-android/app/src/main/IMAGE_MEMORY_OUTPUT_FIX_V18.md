# v18 — corrección de memoria de imagen sobre la v15

La v15 ya eliminaba el camino CPIMG1 -> ByteArray -> IntArray -> Bitmap -> PNG.
Eso era correcto, pero los logs demostraron que no resolvía el problema principal:

- el muestreo llegaba al final;
- el proceso seguía dentro de `generate_image`;
- la fase de salida/decodificación alcanzaba ~4.8 GB de RSS;
- el consumo nativo medido seguía alrededor de ~0.9 GB.

Por tanto, el pico no procedía del `Bitmap` de Kotlin ni del escritor PNG. Se
producía dentro del backend de stable-diffusion.cpp durante la fase posterior
al muestreo, muy probablemente en el decode del VAE.

La v18 mantiene el PNG nativo de la v15 y añade, dentro de stable-diffusion.cpp:

- `max_vram = "1.5"` para limitar la memoria de trabajo gestionada por el backend;
- `disable_prefetch = true` para evitar reservas anticipadas innecesarias;
- `vae_tiling_params.enabled = true`;
- teselas VAE de 256x256 con 50% de solape.

La resolución final sigue siendo 512x512. El objetivo es reducir el pico de
memoria de la decodificación, no degradar la imagen ni añadir otra copia Java.
