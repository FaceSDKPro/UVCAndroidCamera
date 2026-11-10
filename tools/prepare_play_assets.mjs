// Reuse the launcher artwork unchanged; render store layouts from SVG.
// Run with Node and sharp available via NODE_PATH or UVC_SHARP_MODULE.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
const require = createRequire(import.meta.url);
const sharp = require(process.env.UVC_SHARP_MODULE || 'sharp');
const out = new URL('../docs/play/assets/', import.meta.url);
await mkdir(out, { recursive: true });
const mark = 'data:image/png;base64,' + (await readFile(new URL('../sample/src/main/res/drawable-nodpi/ic_launcher_foreground.png', import.meta.url))).toString('base64');
const svg = (w, h, body) => `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
const background = `<defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#f7fafc"/><stop offset="1" stop-color="#e6f1f7"/></linearGradient></defs>`;
const icon = svg(512, 512, `${background}<rect width="512" height="512" fill="url(#bg)"/><image x="-79" y="-66" width="670" height="670" xlink:href="${mark}"/>`);
await sharp(Buffer.from(icon)).ensureAlpha().png().toFile(new URL('icon-512.png', out).pathname);
for (const [locale, title, lines, footer] of [
  ['en-US', 'UVC Dual Camera', ['Two cameras.', 'One workspace.'], 'RGB + infrared  /  USB  /  Offline'],
  ['zh-CN', 'UVC 双目相机', ['双路画面', '一个工作台'], 'RGB 与红外  /  USB  /  离线使用']
]) {
  const feature = svg(1024, 500, `${background}<rect width="1024" height="500" fill="url(#bg)"/>
    <circle cx="815" cy="258" r="218" fill="#dcecf1"/><circle cx="815" cy="258" r="162" fill="#e8f4f5"/>
    <rect x="64" y="72" width="40" height="5" rx="2.5" fill="#07866e"/>
    <g font-family="Arial, PingFang SC, sans-serif" fill="#142b42">
      <text x="64" y="119" font-size="25" font-weight="600">${title}</text>
      <text x="60" y="218" font-size="58" font-weight="700">${lines[0]}</text>
      <text x="60" y="288" font-size="58" font-weight="700">${lines[1]}</text>
      <text x="64" y="387" font-size="20" fill="#426375">${footer}</text>
    </g><image x="482" y="-75" width="660" height="660" xlink:href="${mark}"/>`);
  await sharp(Buffer.from(feature)).flatten({ background:'#eef5f9' }).jpeg({ quality:96, chromaSubsampling:'4:4:4' }).toFile(new URL(`feature-${locale}.jpg`,out).pathname);
}
console.log('Rendered store icon and English/Chinese feature graphics.');

// Optional: frame real device captures. UI pixels are cropped to the app area,
// then scaled without changing text, controls, camera imagery or stream values.
if (process.argv[2]) {
  const { resolve } = await import('node:path');
  const rawRoot = resolve(process.argv[2]);
  const specs = {
    'en-US': [
      ['01-home', 'Your USB camera workspace', 'RGB and infrared on your Android device'],
      ['02-dual', 'RGB and IR, side by side', 'Independent previews. Independent controls.'],
      ['03-settings', 'Set up each camera', 'Resolution, frame rate, format and rotation'],
      ['04-landscape', 'More room in landscape', 'Video, settings and live stream information']
    ],
    'zh-CN': [
      ['01-home', '双路相机，一个工作台', '在 Android 上查看 RGB 与红外画面'],
      ['02-dual', 'RGB 与红外，并排查看', '两路预览，独立控制'],
      ['03-settings', '每路相机，独立设置', '分辨率、帧率、格式与旋转'],
      ['04-landscape', '横屏，留更多空间给画面', '预览、设置与实时状态，一目了然']
    ]
  };
  for (const [locale, shots] of Object.entries(specs)) {
    const shotOut = new URL(`screenshots/${locale}/`, out);
    await mkdir(shotOut,{ recursive:true });
    for (const [name, title, caption] of shots) {
      const input = resolve(rawRoot, locale, `${name}.png`);
      const { width, height } = await sharp(input).metadata();
      const landscape = width > height;
      const W = landscape ? 1920 : 1080, H = landscape ? 1080 : 1920;
      const left = landscape ? 80 : 137, top = landscape ? 320 : 262;
      const shotWidth = W - left * 2;
      const cropped = await sharp(input).extract({ left:0, top:landscape ? 150 : 160, width, height:height-(landscape ? 210 : 232) }).resize({ width:shotWidth }).png().toBuffer();
      const shot = 'data:image/png;base64,' + cropped.toString('base64');
      const { height:shotHeight }=await sharp(cropped).metadata();
      const textX=landscape?80:72;
      const layout = svg(W,H,`${background}<rect width="${W}" height="${H}" fill="url(#bg)"/>
        <g font-family="Arial, PingFang SC, sans-serif" fill="#142b42">
          <text x="${textX}" y="${landscape?91:65}" font-size="${landscape?25:22}" font-weight="600" fill="#07866e">UVC ${locale==='zh-CN'?'双目相机':'Dual Camera'}</text>
          <text x="${textX-3}" y="${landscape?188:144}" font-size="${landscape?70:56}" font-weight="700">${title}</text>
          <text x="${textX}" y="${landscape?248:199}" font-size="${landscape?30:28}" fill="#426375">${caption}</text>
        </g><rect x="${left-2}" y="${top-2}" width="${shotWidth+4}" height="${shotHeight+4}" rx="26" fill="#22374b"/>
        <clipPath id="screen"><rect x="${left}" y="${top}" width="${shotWidth}" height="${shotHeight}" rx="24"/></clipPath>
        <image x="${left}" y="${top}" width="${shotWidth}" height="${shotHeight}" clip-path="url(#screen)" xlink:href="${shot}"/>`);
      await sharp(Buffer.from(layout)).jpeg({ quality:94, chromaSubsampling:'4:4:4' }).toFile(new URL(`${name}.jpg`,shotOut).pathname);
    }
  }
  console.log('Rendered bilingual store screenshots from real device captures.');
}
