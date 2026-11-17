// End-to-end check of in-place editing in Chromium (same engine as Android WebView).
// Run: test/edit-flow.sh
const { chromium } = require('playwright');
const fs = require('fs');
const [html, out] = process.argv.slice(2);
(async () => {
  const [on, collect] = fs.readFileSync(html + '.js', 'utf8').split('\n');
  const b = await chromium.launch();
  const p = await b.newPage();
  await p.setContent(fs.readFileSync(html, 'utf8'));
  await p.evaluate(on);
  const spans = await p.$$('[data-k]');
  // replace the first run's text the way a user would: tap, select all of it, type
  await spans[0].click();
  await p.evaluate(e => { const r = document.createRange(); r.selectNodeContents(e); const s = getSelection(); s.removeAllRanges(); s.addRange(r); }, spans[0]);
  await p.keyboard.type('Değişti  çift boşluk');
  const json = JSON.parse(await p.evaluate(collect));
  await b.close();
  // java.util.Properties format
  const esc = s => s.replace(/[\\\n\r=:#! ]/g, c => ({'\\': '\\\\', '\n': '\\n', '\r': '\\r'})[c] || '\\' + c)
                    .replace(/[^\x20-\x7e]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'));
  fs.writeFileSync(out, Object.entries(json).map(([k, v]) => esc(k) + '=' + esc(v)).join('\n'));
  console.log('first run now:', JSON.stringify(json[Object.keys(json)[0]]));
})();
