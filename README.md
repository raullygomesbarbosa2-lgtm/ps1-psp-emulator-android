# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ e registradores de controle de memória com valores comuns programados pelo BIOS.
- Os três timers têm contador/modo/alvo, clock de sistema e /8, entrada de dot clock, sincronização básica e IRQs conectadas. `Ps1VideoTiming` gera HBlank/VBlank e os encaminha aos timers.
- O perfil de vídeo é provisório: 263/314 linhas por quadro, VBlank padrão na linha 240/288 e janela HBlank aproximada e ajustável; não substitui os registradores de faixa de exibição da GPU. `tickSystemCycles` avança vídeo e timers, mas a CPU ainda não atribui ciclos automaticamente a cada instrução. Renderização, MMIO da GPU, DMA, SPU, CD-ROM, HLE, GTE/COP2 e instruções LWL/LWR/SWL/SWR seguem pendentes; PSP continua stub.
- Testes unitários de CPU, memória, registradores, timers e IRQs passaram no GitHub Actions; isso não é teste em aparelho nem prova de compatibilidade com jogos.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm), [interrupções](https://problemkaputt.de/psxspx-interrupts.htm), [timers](https://www.problemkaputt.de/psxspx-timers.htm) e [timings da GPU](https://problemkaputt.de/psxspx-gpu-timings.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
