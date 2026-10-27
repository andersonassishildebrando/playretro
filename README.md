# Play Retro

Play Retro é um frontend para Android, pensado para toque, que organiza e abre os jogos da sua
coleção multiplataforma em emuladores como o RetroArch.

- Interface 100% touch: toque abre, deslizar navega, toque longo abre o menu de opções.
- Funciona também com controles físicos.
- Pré-configurado para dezenas de sistemas e emuladores; cada sistema pode usar outro emulador
  ou core.
- Scraper de capas e metadados (ScreenScraper e TheGamesDB, com o login de cada usuário).
- Temas: Linear (padrão), Modern e Slate incluídos, mais o baixador de temas.

Application ID: `org.play_retro.frontend`. Dados do usuário ficam em `PlayRetro/` e os jogos em
`ROMs/<sistema>/` no armazenamento do aparelho.

## Instalar

1. Baixe o APK da [última release](https://github.com/andersonassishildebrando/playretro/releases)
   (Android 10 ou mais novo, aparelhos arm64) e instale-o, permitindo a instalação de fontes
   desconhecidas se o Android pedir.
2. Instale os emuladores que vai usar, por exemplo o RetroArch, e dentro do RetroArch baixe os
   cores dos sistemas (menu **Online Updater → Core Downloader**). Sem o core o jogo abre numa
   tela preta.
3. Abra o Play Retro, conceda o acesso aos arquivos e escolha a pasta dos jogos. O app cria uma
   pasta por sistema em `ROMs/` (por exemplo `ROMs/megadrive`, `ROMs/snes`); copie os jogos para
   a pasta correspondente.

## Gestos

| Gesto | Ação |
|---|---|
| Toque | Abrir / confirmar |
| Arrastar ou deslizar com um dedo | Navegar (com inércia) |
| Toque longo | Menu de opções do jogo |
| Toque com dois dedos | Voltar |
| Toque com três dedos | Menu principal |
| Deslizar com dois dedos na horizontal | Trocar de página / sistema |
| Deslizar com dois dedos na vertical | Pular para o início/fim da lista |
| Botão ou gesto "voltar" do Android | Voltar |

Controles físicos (Bluetooth ou USB) também funcionam.

## Versão beta

Esta é a 0.1.1 beta. Problemas conhecidos:

- Não dá para saber de dentro do Play Retro se um core do RetroArch está instalado; se o jogo
  abrir numa tela preta, instale o core no RetroArch.
- Para baixar capas e informações, entre com a sua conta do ScreenScraper (gratuita, em
  screenscraper.fr) em **Scraper → Ajustes de conta**. Para usar o TheGamesDB, informe ali a sua
  chave de API do TheGamesDB.
- Ainda não há atualização automática: novas versões são publicadas nas releases do GitHub.

## Compilar

Veja [PLAYRETRO.md](PLAYRETRO.md) para o passo a passo (dependências nativas e APK).

## Licença e créditos

Código sob licença MIT (ver [LICENSE](LICENSE)). O Play Retro é construído sobre um frontend de
código aberto licenciado sob MIT; os avisos de copyright dos autores originais estão em
[LICENSE](LICENSE) e os créditos completos em [CREDITS.md](CREDITS.md). As licenças das
bibliotecas e recursos de terceiros estão em [licenses](licenses).
