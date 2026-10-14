# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ, e registradores de controle de memória com valores comuns programados pelo BIOS. Temporização de barramento não é simulada.
- O núcleo não está ligado à tela; MMIO de GPU, DMA, timers, SPU, CD-ROM, HLE, GTE/COP2 e instruções LWL/LWR/SWL/SWR ainda não estão implementados. O módulo PSP segue como stub.
- Testes unitários cobrem CPU, RAM, scratchpad, registradores e interrupção externa. O CI está executando essa atualização; não há teste em aparelho nem prova de compatibilidade com jogos.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm) e [interrupções](https://problemkaputt.de/psxspx-interrupts.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
