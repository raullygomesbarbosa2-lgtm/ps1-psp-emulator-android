# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- O módulo PS1 agora contém uma primeira implementação de interpretador inteiro MIPS R3000A e testes unitários para operações básicas, desvio com delay slot, load delay e exceções.
- O interpretador ainda não está ligado à tela nem ao barramento/memória real do PS1; GTE/COP2, HLE, GPU, SPU, CD-ROM, temporização e instruções LWL/LWR/SWL/SWR ainda não estão implementados.
- O módulo PSP continua sendo apenas um stub.
- A etapa de testes unitários do PS1 passou no GitHub Actions; isso não é teste em aparelho nem prova de compatibilidade com jogos.

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Ainda não há APK jogável nem build de release confirmado.
