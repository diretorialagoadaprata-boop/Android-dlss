# Link para Mídia: comparação Windows 2.5 x Android 1.6 (27/10/2026)

## Evidência direta

O usuário confirma que o último executável Windows 2.5 está funcionando corretamente em seu ambiente. Não extrapolar essa confirmação para afirmar que o mesmo vídeo funciona em qualquer IP, rede ou telefone.

**Windows 2.5 inspecionado:** executável PE64 Go 1.23.2, nome de módulo `linkparamidia`, 6.329.856 bytes; SHA-256 `ffe0038ccd0a4ee7177335ed1f58b3c06354524b16ac0fde0fdca9c90811d6a1`. Binário completo disponível como artefato local do desenvolvimento; o código-fonte Go original não está preservado nos arquivos da sessão. O HTML da UI 2.5 está em `linkparamidia_25_ui.html`.

O binário Windows inclui referências verificadas a downloads automáticos:
- `https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe`;
- `https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip`;
- `https://github.com/yt-dlp/FFmpeg-Builds/releases/latest/download/ffmpeg-master-latest-win64-gpl.zip`.

Também contém parâmetros `--js-runtimes`, `--remote-components`, `--cookies-from-browser`, `--audio-format`, `--download-sections` e `--force-keyframes-at-cuts`. A UI 2.5 oculta/retira a seleção obrigatória de navegador.

**Android 1.6 inspecionado:** `linkparamidia/app/build.gradle` inclui biblioteca `io.github.junkfood02.youtubedl-android:library:0.18.1`, `ffmpeg:0.18.1`, com Python e FFmpeg em bibliotecas nativas Android arm64. JavaScript executado com QuickJS-NG embutido, EJS remoto por GitHub, não com Deno. `EngineSetup` tenta atualizar yt-dlp nightly a cada 12 horas, mas captura falhas silenciosamente e não confirma a versão efetiva antes de baixar. Adicionado NewPipeExtractor alternativo, mas teste instrumentado real terminou em `SignInConfirmNotBotException` do YouTube.

**Comparação:** não reutilizar executável `.exe` Windows em Android. Não atribuir a falha conclusivamente ao QuickJS ou ao IP: testes em GitHub Actions receberam `LOGIN_REQUIRED` tanto com yt-dlp + Deno/EJS quanto NewPipe. A prova Windows ocorre em outro ambiente e demonstra que não se deve assumir que o conteúdo seja inacessível universalmente.

## Próximos critérios técnicos, sem novas versões prematuras

1. Instrumentar o Android para registrar versões **efetivas** do yt-dlp e EJS, sucesso/erro de atualização e diagnóstico completo sem revelar tokens/cookies.
2. Reproduzir exatamente as decisões de download do Windows 2.5, com checagem das opções efetivas, sem confundir UI e motor.
3. Investigar Deno ou runtime equivalente no Android com compatibilidade confirmada, sem depender de um executável Windows.
4. Separar bloqueio de rede/YouTube de erro do aplicativo. Confirmar por testes de download real (arquivo MP3 verificado e duração/recorte), não apenas compilação e abertura do APK.
5. Não publicar a 1.6 como homologada. O NewPipe falhou com `SignInConfirmNotBotException` em teste instrumental Android. O teste instrumentado deve falhar formalmente quando mostrar `FAILURES!!!`.
6. Preservar o objetivo 100% gratuito, sem servidor pago, sem navegador obrigatório, interface simples e salvamento na pasta escolhida.

Fontes técnicas: https://github.com/yt-dlp/yt-dlp/wiki/EJS e https://github.com/TeamNewPipe/NewPipeExtractor .
