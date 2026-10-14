# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 contém um interpretador inteiro MIPS R3000A parcial ligado a uma memória inicial: 2 MiB de RAM com espelhos de 8 MiB, aliases KSEG0/KSEG1 e scratchpad de 1 KiB.
- `Ps1Machine` compõe CPU e memória; acessos a hardware MMIO ainda geram bus fault explícito. O núcleo não está ligado à tela; GTE/COP2, HLE, GPU, SPU, CD-ROM, temporização e instruções LWL/LWR/SWL/SWR ainda não estão implementados.
- O módulo PSP continua sendo apenas um stub.
- Os testes unitários de CPU e memória, incluindo a integração CPU/RAM, passaram no GitHub Actions; isso não é teste em aparelho nem prova de compatibilidade com jogos.

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
