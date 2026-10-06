# spaメダルのテクスチャ

`codex exec --enable image_generation`（Codex内蔵image_gen）で生成。`medal-<額面>-source.png`が透過原画（1024×1024）、
`medal_<額面>.png`がゲーム用の128×128 PNG。`sips -z 128 128`で縮小だけを行い、絵柄とアルファを保持する。
`scripts/build-phone-packs.py`でJava版の共通パックと統合版のSpaMedal専用パックへ入れる。
額面ごとに金属の色を変える（1=銅、10=銀、100=金）。緑の内輪はmc.spa77.worksのブランド色（#3aa870・#075130）。

## 生成プロンプト

Use your built-in image generation tool to create THREE separate images, then save them as PNG files exactly at these paths (create folders if needed): assets/spamedal/medal-1-source.png, assets/spamedal/medal-10-source.png, assets/spamedal/medal-100-source.png. Do not edit any other file.

Shared style for all three (they are one set and must look like the same family): a single original game inventory item icon of a round casino token coin called 'spa medal', the physical version of the server currency 'spa coin' on a Japanese Minecraft community server. Straight-on front view of one flat coin, perfectly centered, coin occupies about 85 percent of a square canvas. Thick raised rim with small evenly spaced notches like a casino chip, a thin forest-green inner ring (RGB 58,168,112, dark outline RGB 7,81,48) and in the center a big bold numeral showing the denomination. Above the numeral a tiny simple hot-spring steam symbol (three short wavy lines), no letters. Bold dark outline, simple flat cel shading, one or two large highlights on the metal. Must stay readable at 32px in an inventory: large simple forms, thick lines, the numeral is the dominant element. Truly transparent background with clean alpha silhouette, no scene, no floor, no drop shadow outside the coin, no extra objects, no text other than the numeral, no watermark, no frame. Not pixel art, not voxel art, no imitation of any real casino brand. Square power-of-two PNG.

Per image differences:
1) medal-1-source.png: copper/bronze metal (warm orange-brown), numeral '1'.
2) medal-10-source.png: polished silver metal (cool light gray), numeral '10'.
3) medal-100-source.png: bright gold metal (rich yellow gold), numeral '100' (three digits must all be clearly legible).
