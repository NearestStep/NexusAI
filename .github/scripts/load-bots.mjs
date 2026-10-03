import fs from "fs";
import mineflayer from "mineflayer";

const host = process.env.HOST || "127.0.0.1";
const port = Number(process.env.PORT || "25565");
const count = Number(process.env.BOTS || "20");
const talkFlag = process.env.TALK_FLAG || "";
const reportPath = process.env.BOT_REPORT || "bots.json";
const requested = process.env.MC_VERSION || "";

const bots = [];

function writeReport() {
  const payload = {
    online: bots.filter((entry) => entry.spawned).length,
    messages: bots.reduce((sum, entry) => sum + entry.messages, 0),
    errors: bots.flatMap((entry) => entry.errors).slice(0, 20),
  };
  fs.writeFileSync(reportPath, JSON.stringify(payload));
}

for (let i = 0; i < count; i++) {
  const name = `NaiBot${String(i + 1).padStart(2, "0")}`;
  const entry = { name, spawned: false, messages: 0, errors: [] };
  const options = {
    host,
    port,
    username: name,
    auth: "offline",
  };
  if (requested) {
    options.version = requested;
  }
  const bot = mineflayer.createBot(options);
  bot.on("spawn", () => {
    entry.spawned = true;
    writeReport();
  });
  bot.on("message", () => {
    entry.messages += 1;
  });
  bot.on("kicked", (reason) => {
    entry.errors.push(`kicked ${reason}`);
    writeReport();
  });
  bot.on("error", (error) => {
    entry.errors.push(String(error));
    writeReport();
  });
  bots.push(entry);
  setInterval(() => {
    if (!entry.spawned || !talkFlag || !fs.existsSync(talkFlag)) {
      return;
    }
    bot.chat(`/nai talk harbor hello ${Date.now()}`);
  }, 3000);
}

setInterval(writeReport, 1000);
writeReport();
