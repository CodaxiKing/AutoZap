# PinSave

App Android para baixar vídeos do Pinterest e postar no Instagram ou no TikTok.

## Instalar

1. No celular, abra **Releases** deste repositório e baixe o `PinSave.apk`.
2. Abra o arquivo e permita "instalar apps desconhecidos" quando o Android pedir.

Requer Android 10 ou mais novo.

## Como usar

Há três jeitos de baixar um vídeo:

- **Pop-up ao copiar o link** — no Pinterest, toque em Compartilhar → Copiar link e
  o PinSave pergunta na hora se você quer baixar. Precisa ser ativado uma vez: abra o
  PinSave, toque em "Ativar pop-up ao copiar link" e ligue o serviço
  "PinSave: pop-up ao copiar link" em Acessibilidade. Se o Android mostrar
  "Configuração restrita", vá em Configurações → Apps → PinSave → ⋮ →
  Permitir configurações restritas e tente de novo.
- **Compartilhar** — no Pinterest, toque em Compartilhar → Mais → "Baixar com PinSave".
- **Abrir o app** — com um link copiado, o PinSave pergunta se quer baixar assim que abre.
  Também dá para colar o link no campo e tocar em Baixar.

Depois do download o app pergunta onde postar (Instagram, TikTok ou outro app) e abre
o app escolhido já com o vídeo anexado. Os vídeos ficam na galeria, em `Movies/PinSave`,
e aparecem na lista do app para postar depois.

### Por que o serviço de acessibilidade?

Desde o Android 10, apps em segundo plano não podem ler a área de transferência. O
serviço só percebe o toque em "Copiar link" dentro do Pinterest (não lê a tela nem o
que você digita) e abre o pop-up, que então consegue ler o link copiado.

## Compilar

```
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`. Cada push também gera o APK pelo
GitHub Actions (`.github/workflows/build.yml`).

## Aviso

Baixe e reposte apenas vídeos seus ou que você tenha permissão para usar.
