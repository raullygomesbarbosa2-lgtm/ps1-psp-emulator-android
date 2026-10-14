# PS1 + PSP — esqueleto Android nativo

Esta pasta contém uma fundação de projeto, não um emulador funcional nem um APK.

- App Android nativo em Java, sem WebView.
- Bibliotecas PS1 e PSP separadas, cada uma com seu módulo de núcleo.
- A tela permite selecionar um arquivo local para cada plataforma; a seleção não inicia nem valida o jogo.
- Os núcleos são stubs que informam explicitamente que a emulação ainda não foi implementada.
- Próximo trabalho real: preparar build reproducível, testes de componentes e começar a implementação HLE/CPU do PS1.

## Build

Abra esta pasta como projeto no Android Studio com JDK 17 e Android SDK 35. O Gradle Wrapper ainda não está incluído. Não há build nem teste de aparelho confirmado nesta etapa.
