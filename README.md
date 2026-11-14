# PinSave

App Android para baixar vídeos do Pinterest e postar no Instagram ou no TikTok.

## Instalar

1. No celular, abra **Releases** deste repositório e baixe o `PinSave.apk`.
2. Abra o arquivo e permita "instalar apps desconhecidos" quando o Android pedir.

Requer Android 10 ou mais novo.

## Como usar

Há três jeitos de baixar um vídeo:

- **Atalho na barra de notificações** — abra o PinSave uma vez e toque em "Ativar atalho
  na barra de notificações". Depois, no Pinterest, toque em Compartilhar → Copiar link,
  puxe a barra de notificações e toque em "Copiou um link do Pinterest?": o pop-up
  pergunta se você quer baixar. Também existe o bloco "Baixar pin" para adicionar nas
  Configurações rápidas.
- **Compartilhar** — no Pinterest, toque em Compartilhar → Mais → "Baixar com PinSave".
- **Abrir o app** — com um link copiado, o PinSave pergunta se quer baixar assim que abre.
  Também dá para colar o link no campo e tocar em Baixar.

Depois do download o app pergunta onde postar (Instagram, TikTok ou outro app) e abre
o app escolhido já com o vídeo anexado. Os vídeos ficam na galeria, em `Movies/PinSave`,
e aparecem na lista do app para postar depois.

### Legendas

Cada vídeo tem a sua legenda. Ela começa com o título e a descrição do pin (quando o
Pinterest fornece) e pode ser editada no pop-up, logo depois do download, ou na lista do
app. Em "Hashtags fixas" você define hashtags que entram no fim de toda legenda.

Instagram e TikTok não aceitam legenda enviada por outro app, então ao tocar em postar
o PinSave copia a legenda: é só colar no campo de legenda da publicação.

### Por que o pop-up não abre sozinho ao copiar?

Desde o Android 10, apps em segundo plano não podem ler a área de transferência. O único
jeito de contornar seria um serviço de acessibilidade, mas o Google Play Protect bloqueia
a instalação de apps de fora da Play Store que pedem esse acesso. Por isso o pop-up é
aberto com um toque na notificação.

## Compilar

```
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`. Cada push também gera o APK pelo
GitHub Actions (`.github/workflows/build.yml`).

## Aviso

Baixe e reposte apenas vídeos seus ou que você tenha permissão para usar.
