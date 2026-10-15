# Projeto: emulador Android de PlayStation One — do zero

## Objetivo confirmado

- Aplicativo **Android nativo**, sem WebView.
- Um único sistema no escopo: PlayStation 1 (PS1/PlayStation One), com biblioteca e núcleo próprios.
- Implementar os núcleos de emulação do zero, sem incorporar código de outros emuladores.
- O PS1 deve iniciar sem exigir uma imagem de BIOS externa, por meio de uma camada HLE (High-Level Emulation) própria. HLE não garante compatibilidade total: alguns jogos podem depender de comportamentos específicos da BIOS original.
- Não incluir jogos ou BIOS no aplicativo. O usuário seleciona arquivos que já possui.

## Arquitetura inicial

```text
app/
  src/main/                 Interface Android nativa e ciclo de vida
  ├── catalog/              Biblioteca PS1
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

renderer/
  android/                  Apresentação de imagem e áudio no Android

validation/
  ps1/                      Testes determinísticos e verificações por componente
```

A estrutura é uma proposta de organização. `engine/ps1` conecta CPU MIPS R3000A parcial, RAM/scratchpad, interrupções, timers e GPU parcial. Um carregador PS-X EXE inicia uma sessão experimental; o scheduler provisório avança um ciclo de sistema por instrução e emite snapshots ao completar quadros. `Ps1VideoFrameView` os apresenta em Canvas nativo. O HLE atende a rotinas A0 de heap, memória e strings básicas, serviços B0 de eventos/timers e C0 InitRCnt; não emula arquivos/stdio A0, HLE completo, jogos de disco ou compatibilidade de jogos.

## Sequência de desenvolvimento

1. **Fundação Android:** projeto nativo com navegação e biblioteca exclusivas do PS1.
2. **Catálogo local:** importar arquivos escolhidos pelo usuário por seletor do Android, sem baixar ou fornecer jogos.
3. **Base verificável do PS1:** CPU, memória, IRQs, timers, GP1, transferências GP0/VRAM, primitivas, carregamento PS-X EXE, quadros de sessão e eventos BIOS parciais têm testes unitários. Próximo: ampliar HLE, temporização e dispositivos antes de aumentar a compatibilidade.
4. **HLE e dispositivos:** inicialização HLE, DMA, controles e leitura de disco; integrar eventos reais de vídeo aos timers e registrar claramente o que ainda não é compatível.
5. **Imagem e áudio:** GPU e SPU próprios; primeiro objetivo é executar conteúdo de teste simples e documentado, não prometer compatibilidade universal.
6. **Teste em aparelhos:** validar desempenho, estabilidade e compatibilidade em Android antigo e recente; corrigir antes de chamar qualquer build de jogável.

## Critérios para chamar uma versão de jogável

- Compila como APK instalável e abre no aparelho de teste.
- A biblioteca PS1 aceita um arquivo local escolhido pelo usuário, sem exigir BIOS externa.
- Existe um teste reproduzível de inicialização e execução; resultados e limitações são informados.
- Controles, pausa/retomada e encerramento de sessão funcionam sem travar o app.
- Compatibilidade limitada é descrita sem alegar que todos os jogos funcionam.

## Limites e decisões técnicas

- A primeira meta é uma base de PS1 testável; um emulador completo exige implementar corretamente CPU, vídeo, áudio, CD-ROM e temporização, não apenas criar telas.
- HLE pode substituir serviços comuns de BIOS, mas não é garantia de que todo jogo funcione sem a BIOS original.
- `Ps1Machine.tickSystemCycles` avança timers e raster; GP1 define região/faixas. GP0 preenche/copia/transfere VRAM e rasteriza primitivas opacas sem textura; `renderDisplayFrame()` produz snapshots ARGB exibidos pela View Android. A tela pode receber quadros da sessão experimental PS-X EXE. O clock é aproximado; o HLE é parcial, sem CD-ROM, arquivos/stdio A0, callbacks BIOS, texturas, semitransparência, FIFO/DMA, APK jogável ou teste em aparelho.
- Antes de qualquer distribuição, validar requisitos legais aplicáveis a marcas, formatos de arquivo e distribuição do aplicativo. O app não deve fornecer firmware protegido ou jogos.
