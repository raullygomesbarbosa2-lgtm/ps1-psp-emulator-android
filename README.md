# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ e registradores de controle de memória com valores comuns programados pelo BIOS.
- Os três timers têm contador/modo/alvo, clocks de sistema, /8 e dot clock, sincronização básica e IRQs. O raster NTSC/PAL tem 263/314 linhas; GP1 controla modo/faixas e alimenta HBlank/VBlank, ainda como modelo parcial.
- A GPU tem VRAM 1024×512 RGB555, transferência A0h/C0h, cópia/preenchimento e rasterização inicial de polígonos flat/Gouraud, segmentos/polilinhas e retângulos **opacos e sem textura**. `renderDisplayFrame()` gera um snapshot ARGB8888 da VRAM conforme GP1, mas ainda não o conecta à tela Android. Sem blend/semitransparência, textura, FIFO/DMA ou execução de jogos: continua não jogável. Também faltam scheduler de CPU, HLE, GTE/COP2, LWL/LWR/SWL/SWR, SPU e CD-ROM; PSP continua stub.
- A suíte automatizada cobre CPU, barramento, timers, GP1, operações GP0/VRAM, primitivas e conversão de quadro. A execução [GitHub Actions #38664339041](https://github.com/raullygomesbarbosa2-lgtm/ps1-psp-emulator-android/actions/runs/38664339041) passou em `:engine:ps1:test` para o commit `84b5c73`. Isso valida testes unitários do núcleo, não compatibilidade com jogos nem execução em aparelho; o app ainda não apresenta quadros em tempo real.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm), [interrupções](https://problemkaputt.de/psxspx-interrupts.htm), [timers](https://www.problemkaputt.de/psxspx-timers.htm), [timings da GPU](https://problemkaputt.de/psxspx-gpu-timings.htm) e [comandos GP1](https://problemkaputt.de/psxspx-gpu-display-control-commands-gp1.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
