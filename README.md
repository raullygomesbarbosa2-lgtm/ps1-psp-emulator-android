# PlayStation One — base Android nativa

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- App com foco exclusivo no núcleo PlayStation One (PS1), separado da interface Android.
- A tela permite selecionar arquivos locais. Apenas um PS-X EXE pode ser carregado e iniciado em sessão experimental; BIN/CUE/ISO não são suportados e selecionar um arquivo não o inicia automaticamente.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial, 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB (KUSEG/KSEG0, não KSEG1).
- `Ps1Machine` conecta CPU e memória; o barramento mapeia I_STAT/I_MASK, fontes de IRQ e registradores de controle de memória com valores comuns programados pelo BIOS.
- Os três timers têm contador/modo/alvo, clocks de sistema, /8 e dot clock, sincronização básica e IRQs. O raster NTSC/PAL tem 263/314 linhas; GP1 controla modo/faixas e alimenta HBlank/VBlank, ainda como modelo parcial.
- A GPU tem VRAM 1024×512 RGB555, transferência A0h/C0h, cópia/preenchimento e rasterização inicial de polígonos flat/Gouraud, segmentos/polilinhas e retângulos **opacos e sem textura**. `renderDisplayFrame()` gera snapshots ARGB8888; `Ps1VideoFrameView` os apresenta numa View Android nativa. Um carregador PS-X EXE experimental executa um subconjunto MIPS e envia quadros da sessão à View; também há um padrão sintético de diagnóstico. Isso não equivale a compatibilidade com jogos. A CPU inclui LWL/LWR/SWL/SWR com testes de lanes little-endian e uma GTE inicial: transferências COP2, registradores e NCLIP; ainda não oferece os demais comandos GTE. O HLE parcial despacha A0 de heap (InitHeap/malloc/free/calloc/realloc), memória (bcopy/bzero/memcpy/memset/memmove/memcmp/memchr), strings básicas (strlen/strcpy/strcmp/strcat e afins), serviços B0 de eventos/timers e InitRCnt C0; chamadas não implementadas retornam -1, WaitEvent não bloqueia e callbacks de eventos não executam. A temporização usa provisoriamente um ciclo por instrução; faltam A0 de arquivos/stdio, HLE completo, CD-ROM, blend/semitransparência, textura, FIFO/DMA, temporização precisa, demais comandos GTE e SPU.
- O CI executa os testes unitários do núcleo e compila o app Android com `:app:assembleDebug`. Isso verifica testes/compilação, não compatibilidade com jogos nem execução em aparelho.

Referências técnicas: [PSX-SPX: mapa de memória](https://problemkaputt.de/psxspx-memory-map.htm), [registradores de controle de memória](https://problemkaputt.de/psxspx-memory-control.htm), [interrupções](https://problemkaputt.de/psxspx-interrupts.htm), [timers](https://www.problemkaputt.de/psxspx-timers.htm), [timings da GPU](https://problemkaputt.de/psxspx-gpu-timings.htm) e [comandos GP1](https://problemkaputt.de/psxspx-gpu-display-control-commands-gp1.htm).

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
