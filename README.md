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

## Compilar

Veja [PLAYRETRO.md](PLAYRETRO.md) para o passo a passo (dependências nativas e APK).

## Licença e créditos

Código sob licença MIT (ver [LICENSE](LICENSE)). O Play Retro é construído sobre um frontend de
código aberto licenciado sob MIT; os avisos de copyright dos autores originais estão em
[LICENSE](LICENSE) e os créditos completos em [CREDITS.md](CREDITS.md). As licenças das
bibliotecas e recursos de terceiros estão em [licenses](licenses).
