# Projeto: emulador Android de PS1 e PSP — do zero

## Objetivo confirmado

- Aplicativo **Android nativo**, sem WebView.
- Dois sistemas independentes: PlayStation 1 (PS1/PlayStation One) e PlayStation Portable (PSP), cada um com sua própria biblioteca e núcleo de emulação.
- Implementar os núcleos de emulação do zero, sem incorporar código de outros emuladores.
- O PS1 deve iniciar sem exigir uma imagem de BIOS externa, por meio de uma camada HLE (High-Level Emulation) própria. HLE não garante compatibilidade total: alguns jogos podem depender de comportamentos específicos da BIOS original.
- Não incluir jogos ou BIOS no aplicativo. O usuário seleciona arquivos que já possui.

## Arquitetura inicial

```text
app/
  src/main/                 Interface Android nativa e ciclo de vida
  ├── catalog/              Bibliotecas separadas: PS1 e PSP
  ├── files/                Seleção de arquivos via Storage Access Framework
  ├── settings/             Preferências por sistema e por jogo
  └── emulation/            Contrato comum para iniciar, pausar e encerrar uma sessão

engine/
  common/                   Tipos, clock, estado da sessão e interfaces compartilhadas
  ps1/                      Núcleo PS1 próprio, sem dependência de BIOS externa
    cpu/                    Interpretador MIPS R3000A e exceções
    memory/                 Mapa de memória e barramentos
    gpu/                    Comandos gráficos e renderização
    cdrom/                  Leitura e controle de mídia
    dma/                    Canais e transferências DMA
    timers/                  Temporizadores e interrupções
    spu/                    Áudio
    input/                  Controles e mapeamento de botões
    hle/                    Serviços de inicialização e BIOS HLE
  psp/                      Núcleo PSP próprio, etapa posterior e independente

renderer/
  android/                  Apresentação de imagem e áudio no Android

validation/
  ps1/                      Testes determinísticos e verificações por componente
  psp/                      Testes do núcleo PSP quando essa etapa começar
```

A estrutura é uma proposta de organização. `engine/ps1` conecta CPU MIPS R3000A parcial, RAM/scratchpad, interrupções, timers e GPU parcial. Um carregador de PS-X EXE pode iniciar uma sessão experimental; o scheduler provisório avança um ciclo de sistema por instrução e emite snapshots quando o raster completa um quadro. `Ps1VideoFrameView` os apresenta em Canvas nativo. Isso não oferece suporte a jogos de disco, HLE completo ou compatibilidade de jogos.

## Sequência de desenvolvimento

1. **Fundação Android:** projeto nativo, telas iniciais e navegação entre as bibliotecas PS1 e PSP.
2. **Catálogo local:** importar jogos escolhidos pelo usuário por seletor de arquivos do Android; manter listas separadas, sem baixar ou fornecer jogos.
3. **Base verificável do PS1:** CPU, memória, IRQs, timers, GP1, transferências GP0/VRAM, primitivas, carregamento PS-X EXE e entrega de quadros de sessão têm testes unitários. Próximo: completar temporização, HLE e dispositivos necessários antes de ampliar a compatibilidade.
4. **HLE e dispositivos:** inicialização HLE, DMA, controles e leitura de disco; integrar eventos reais de vídeo aos timers e registrar claramente o que ainda não é compatível.
5. **Imagem e áudio:** GPU e SPU próprios; primeiro objetivo é executar conteúdo de teste simples e documentado, não prometer compatibilidade universal.
6. **Teste em aparelhos:** validar desempenho, estabilidade e compatibilidade em Android antigo e recente; corrigir antes de chamar qualquer build de jogável.
7. **PSP:** iniciar o núcleo PSP como projeto separado somente depois de a fundação e o fluxo de testes estarem estabelecidos. É um escopo muito maior e não deve ser apresentado como concluído junto com o primeiro protótipo PS1.

## Critérios para chamar uma versão de jogável

- Compila como APK instalável e abre no aparelho de teste.
- A biblioteca PS1 aceita um arquivo local escolhido pelo usuário, sem exigir BIOS externa.
- Existe um teste reproduzível de inicialização e execução; resultados e limitações são informados.
- Controles, pausa/retomada e encerramento de sessão funcionam sem travar o app.
- Compatibilidade limitada é descrita sem alegar que todos os jogos funcionam.

## Limites e decisões técnicas

- A primeira meta é uma base de PS1 testável; um emulador completo exige implementar corretamente CPU, vídeo, áudio, CD-ROM e temporização, não apenas criar telas.
- HLE pode substituir serviços comuns de BIOS, mas não é garantia de que todo jogo funcione sem a BIOS original.
- `Ps1Machine.tickSystemCycles` avança timers e raster; GP1 define região/faixas. GP0 preenche/copia/transfere VRAM e rasteriza primitivas opacas sem textura; `renderDisplayFrame()` produz snapshots ARGB exibidos pela View Android. A tela pode receber quadros da sessão experimental de PS-X EXE, mas o clock é aproximado e não há HLE completo, CD-ROM, texturas, semitransparência, FIFO/DMA, APK jogável nem teste em aparelho.
- Antes de qualquer distribuição, validar requisitos legais aplicáveis a marcas, formatos de arquivo e distribuição do aplicativo. O app não deve fornecer firmware protegido ou jogos.
