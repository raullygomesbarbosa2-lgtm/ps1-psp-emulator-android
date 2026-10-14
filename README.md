# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ e registradores de controle de memória com valores comuns programados pelo BIOS.
- Os três timers têm contador/modo/alvo, clock de sistema e /8, entrada de dot clock, sincronização básica e IRQs conectadas. `Ps1VideoTiming` gera HBlank/VBlank; o subconjunto GP1 da GPU mapeia os comandos 03h–08h e aplica as faixas de exibição 06h/07h ao raster.
- O raster usa 263/314 linhas e clock NTSC/PAL; faixas GP1 são configuráveis, mas o vínculo com blanking segue parcial e não há imagem renderizada. `tickSystemCycles` avança vídeo e timers, mas a CPU ainda não atribui ciclos automaticamente a cada instrução. GP0, VRAM/renderização, FIFO, DMA, SPU, CD-ROM, HLE, GTE/COP2 e instruções LWL/LWR/SWL/SWR seguem pendentes; PSP continua stub.
- Testes unitários de CPU, memória, registradores, timers, raster, GP1 e IRQs passaram no GitHub Actions (run [#38647232417](https://github.com/raullygomesbarbosa2-lgtm/ps1-psp-emulator-android/actions/runs/38647232417), commit `903055a`); isso não é teste em aparelho nem prova de compatibilidade com jogos.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm), [interrupções](https://problemkaputt.de/psxspx-interrupts.htm), [timers](https://www.problemkaputt.de/psxspx-timers.htm), [timings da GPU](https://problemkaputt.de/psxspx-gpu-timings.htm) e [comandos GP1](https://problemkaputt.de/psxspx-gpu-display-control-commands-gp1.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
