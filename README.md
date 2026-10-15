# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ e registradores de controle de memória com valores comuns programados pelo BIOS.
- Os três timers têm contador/modo/alvo, clocks de sistema, /8 e dot clock, sincronização básica e IRQs. O raster NTSC/PAL tem 263/314 linhas; GP1 controla modo/faixas e alimenta HBlank/VBlank, ainda como modelo parcial.
- A GPU tem VRAM própria de 1024×512 pixels RGB555, GP0 para preenchimento/cópia de áreas, upload/download A0h/C0h, IRQ e consumo de pacotes básicos de primitivas. **Não rasteriza polígonos, linhas ou texturas, não tem FIFO/DMA nem saída de imagem**; isso não roda jogos. O agendamento de ciclos por instrução da CPU, HLE, GTE/COP2, LWL/LWR/SWL/SWR, SPU e CD-ROM seguem pendentes; PSP continua stub.
- Os testes de CPU, memória, timers, raster, GP1, IRQ e GP0/VRAM passaram no CI ([run #38662322697](https://github.com/raullygomesbarbosa2-lgtm/ps1-psp-emulator-android/actions/runs/38662322697), commit `36a5cd2`). Isso valida somente testes unitários em CI; não é teste em aparelho nem prova de compatibilidade com jogos.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm), [interrupções](https://problemkaputt.de/psxspx-interrupts.htm), [timers](https://www.problemkaputt.de/psxspx-timers.htm), [timings da GPU](https://problemkaputt.de/psxspx-gpu-timings.htm) e [comandos GP1](https://problemkaputt.de/psxspx-gpu-display-control-commands-gp1.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
