// Spring statik fayllarni o'zi siqmaydi: .gz nusxalarini oldindan tayyorlaymiz,
// backend esa brauzer qabul qilsa ularni beradi (EncodedResourceResolver)
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { gzipSync } from "node:zlib";

const walk = (dir) =>
  readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });

const OUT = "../backend/src/main/resources/META-INF/devpanel-ui";

for (const file of walk(OUT)) {
  if (/\.(js|css|html|svg|json)$/.test(file)) {
    writeFileSync(`${file}.gz`, gzipSync(readFileSync(file), { level: 9 }));
  }
}
