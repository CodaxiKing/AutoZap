# PinSave

App Android para baixar vídeos e imagens do Pinterest, Instagram e TikTok, criar vídeos
e carrosséis com frases, e postar no Instagram ou no TikTok.

## Instalar

1. No celular, abra **Releases** deste repositório e baixe o `PinSave.apk`.
2. Abra o arquivo e permita "instalar apps desconhecidos" quando o Android pedir.

Requer Android 10 ou mais novo.

## Como usar

Há quatro jeitos de baixar um pin:

- **Botão flutuante** (o mais rápido) — abra o PinSave uma vez, toque em "Ativar botão
  flutuante" e dê a permissão "Sobrepor a outros apps". Uma bolinha fica sobre a tela:
  no Pinterest, toque em Compartilhar → Copiar link e depois na bolinha, e o download
  começa. Arraste a bolinha para mudar de lugar.
- **Atalho na barra de notificações** — abra o PinSave uma vez e toque em "Ativar atalho
  na barra de notificações". Depois, no Pinterest, toque em Compartilhar → Copiar link,
  puxe a barra de notificações e toque em "Copiou um link do Pinterest?": o pop-up
  pergunta se você quer baixar. Também existe o bloco "Baixar pin" para adicionar nas
  Configurações rápidas.
- **Compartilhar** — no Pinterest, toque em Compartilhar → Mais → "Baixar com PinSave".
- **Abrir o app** — com um link copiado, o PinSave pergunta se quer baixar assim que abre.
  Também dá para colar o link no campo e tocar em Baixar.

Depois do download o app mostra a legenda e pergunta onde postar. No Instagram dá para
ir direto para Reels, Stories ou Feed. Os arquivos ficam na galeria (`Movies/PinSave` e
`Pictures/PinSave`) e aparecem na lista do app para postar depois.

### Instagram

Links de posts, Reels e carrosséis de contas públicas funcionam do mesmo jeito que os do
Pinterest: copie o link (ou use Compartilhar → "Baixar com PinSave") e a legenda original
vem junto. Contas privadas, Stories e destaques não funcionam, e o Instagram às vezes
bloqueia o acesso sem login; nesse caso o app avisa e vale tentar de novo mais tarde.

### Legendas com IA

Em "Legendas com IA" você cola uma chave da API da OpenAI. Com ela, todo download e todo
vídeo ou carrossel criado já vem com uma legenda escrita pela IA no tom da página, com
hashtags. A frase do vídeo e a legenda original servem de referência. Em qualquer campo de
legenda, "✨ Gerar legenda com IA" escreve outra versão.

A chave fica guardada só no aparelho. Não a coloque no código nem no repositório: chave
exposta em repositório público é usada por terceiros e cancelada pela OpenAI. O uso é
cobrado na conta da OpenAI (frações de centavo por legenda).

### Marca d'água

Com "Marca d'água com a logo" ativada, todo download novo recebe a logo meio transparente,
centralizada na parte de baixo do quadro. Em vídeos isso exige recodificar o arquivo no
aparelho, o que leva mais ou menos o tempo de duração do vídeo. A logo fica em
`app/src/main/res/drawable-nodpi/watermark.png`. GIFs são salvos sem a marca.

### TikTok

Links de vídeos e de posts de fotos do TikTok são baixados sem a marca d'água do TikTok:
o app busca o arquivo original, não a cópia de download. Vídeos privados não funcionam, e
o TikTok às vezes bloqueia o acesso; nesse caso o app avisa.

### Criar vídeo e criar carrossel

Os botões "Criar vídeo" e "Criar carrossel" montam conteúdo novo. Você escolhe o tema
(carros de luxo, dinheiro, relógios, cidade à noite etc.), escreve a frase — no carrossel,
uma por linha — e o app sugere fundos do banco. "Trocar" busca outro fundo até você gostar.
O resultado sai escurecido, com a frase no meio e a logo, e a legenda fica pronta para colar.

Os fundos vêm do Pexels, que exige uma chave de API gratuita (pexels.com/api). O app já vem
com uma chave embutida (em `Pexels.kt`); "Trocar a chave do Pexels" no gerador usa outra. Os vídeos do banco não têm música.

### Montagem com música

"Criar montagem com música" faz um vídeo em que as cenas trocam no ritmo da música. Você
escolhe a música (um áudio extraído pelo app ou qualquer áudio/vídeo do celular), a duração
máxima, o ritmo das trocas e o tema. O app acha as batidas, divide o vídeo em partes e
sugere um trecho do banco para cada uma; "Trocar" muda o trecho de cada parte. O resultado
sai em 1080x1920 com a música, a frase (opcional) e a logo.

### Extrair música

No menu ⋮ de um vídeo, "Extrair o áudio (música)" salva só o som em `Music/PinSave`. Em
"Músicas extraídas", na tela principal, dá para extrair de qualquer vídeo do celular, ouvir
e excluir. Os áudios extraídos aparecem na escolha de música da montagem.

### Banco de frases

Em "Banco de frases" você guarda suas frases (cole várias de uma vez, uma por linha). No
gerador, "Usar frases do banco" deixa escolher ou sortear as que ainda não foram usadas; no
pop-up de download, "Escolher do banco de frases" preenche a frase. As usadas ganham um ✓.

### Hashtags por tema

Cada tema do gerador (máfia, carros de luxo, dinheiro etc.) tem um conjunto de hashtags que
entra sozinho no fim da legenda. Dá para editar o conjunto de cada tema na tela de criação;
a mudança fica guardada. As hashtags fixas continuam entrando depois, em tudo.

### Criar vários vídeos de uma vez

"Criar vários vídeos de uma vez" recebe até 20 frases, uma por linha, e gera um vídeo para
cada uma, com fundos diferentes do mesmo tema. Cada fundo pode ser trocado antes de gerar.
Os vídeos entram na fila e aparecem na lista conforme ficam prontos; a legenda de cada um
é a própria frase, mais o texto extra que você escrever.

### Lembretes de postagem

Em "Lembretes de postagem" você define horários diários. Na hora, o app avisa e abre o item
mais antigo que ainda não foi postado, já na tela de legenda e postagem. O Android pode
atrasar o aviso em alguns minutos para poupar bateria, e é preciso permitir as notificações.

### Frase no vídeo

Com "Pedir a frase do vídeo antes de baixar" ativado, o pop-up traz um campo para a frase.
Ela é gravada em branco, pequena, no meio do quadro, e nos vídeos surge aos poucos nos
primeiros segundos. Deixe em branco para baixar sem frase.
Em algo já baixado, use o menu ⋮ → "Adicionar frase": o arquivo é substituído pela versão
com a frase. A frase gravada não pode ser removida depois.

### Imagens e carrosséis

Pins sem vídeo são baixados como imagem. Em carrosséis, todas as imagens são baixadas e
podem ser enviadas juntas.

### Fila de downloads

No pop-up, "Em 2º plano" coloca o download na fila e libera a tela; uma notificação avisa
quando terminar e abre a legenda. No app, dá para colar vários links de uma vez no campo e
tocar em Baixar. Para receber os avisos, permita as notificações do PinSave.

### O que já foi postado

Cada item ganha um selo "✓ Instagram" ou "✓ TikTok" quando você o envia para o app, e o
filtro "Ainda não postei" esconde os que já foram. O selo marca que o arquivo foi enviado
ao app, não que a publicação foi concluída; dá para desmarcar no menu ⋮.

### Legendas

Cada arquivo tem a sua legenda. Ela começa com o título e a descrição do pin (quando o
Pinterest fornece) e pode ser editada no pop-up, logo depois do download, ou na lista do
app. Em "Hashtags fixas" você define hashtags que entram no fim de toda legenda, e em
"Modelos de legenda" você guarda textos prontos para inserir com um toque.

Instagram e TikTok não aceitam legenda enviada por outro app, então ao tocar em postar
o PinSave copia a legenda: é só colar no campo de legenda da publicação.

### Por que o pop-up não abre sozinho ao copiar?

Desde o Android 10, apps em segundo plano não podem ler a área de transferência. O único
jeito de contornar seria um serviço de acessibilidade, mas o Google Play Protect bloqueia
a instalação de apps de fora da Play Store que pedem esse acesso. Por isso o pop-up é
aberto com um toque no botão flutuante ou na notificação.

## Compilar

```
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`. Cada push também gera o APK pelo
GitHub Actions (`.github/workflows/build.yml`).

## Aviso

Baixe e reposte apenas vídeos seus ou que você tenha permissão para usar.
