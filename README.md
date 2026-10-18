# tokaStorage

Java 21 共用儲存 library prototype，**不是 Nukkit 插件，不要放入伺服器 plugins**。提供 SQLite、JSON、YAML 三種 backend，預設 SQLite。第一個用途是新 toka 插件的維護狀態、待辦、返回位置及操作紀錄；尚未接管 zero 原有 MySQL 或其領地資料。

## API

Maven 座標：`dev.toka:tokaStorage:<版本>`，版本以 `pom.xml` 為準；以下 JAR 檔名中的 `<版本>` 請換成實際建置版本。呼叫方將本 library 及其依賴一起打包，避免漏掉 JDBC driver。對 SnakeYAML 可自行 relocate；SQLite 的 native resources、driver class 與 service metadata 必須保留。產物有薄 `output/tokaStorage-<版本>.jar` 與自帶依賴的 `output/tokaStorage-<版本>-all.jar`；後者同時提供離線 CLI，SnakeYAML 已 relocate。

```java
import dev.toka.storage.*;
import com.google.gson.JsonObject;
import java.nio.file.Path;

try (DocumentStore store = DocumentStore.open(new StoreConfig(Path.of("data/storage"), 1))) {
    JsonObject state = new JsonObject();
    state.addProperty("world", "HePingZhiDi");
    store.save("state", state);
    JsonObject saved = store.load("state").orElseThrow();
}
```

`StoreConfig(Backend, Path, int schemaVersion)` 明確指定格式；`StoreConfig(Path,int)` 預設 SQLite。`load` 回傳 `Optional<JsonObject>`，另有 `save`、`saveAll`、`delete`、`keys`、`snapshot` 與 `close`，I/O 失敗以 `IOException` 回報。資料 key 限 `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`，沒有任意路徑讀寫。回傳及儲存資料皆與呼叫方的 JsonObject 分離。

每個儲存目錄只能由一個本 library instance 開啟，使用 OS file lock。backend 不同但已有其他格式的儲存檔時會拒絕開啟，不能單純改設定得到空資料庫。呼叫方負責完整關閉，再明確搬遷。schemaVersion 必須相符，不猜測 schema 升級，也不自動匯入插件舊檔。

## 持久化與效能範圍

- SQLite 單次 `save` 是單行自動提交 auto-commit；`saveAll` 在同一資料庫交易中批次寫入，失敗時嘗試回復。使用 `synchronous FULL` 及 `DELETE journal`；`load` 與 `keys` 查對應欄位，不為單筆讀取掃描所有 payload。
- JSON／YAML 包含共同 envelope：`schemaVersion`、`documents`。每次修改會讀寫整份檔案，UTF-8、flush／force 後 atomic rename。檔案系統不支援 atomic move 時回報失敗，不默默改為非原子覆寫。
- Windows 暫時 sharing denial 的 `AccessDeniedException` 最多重試四次，等待 25／50／100／200 ms；始終保持 atomic rename。永久拒絕、其他 I/O 錯誤或中斷仍停止，保留原檔；中斷旗標不會被吞掉。
- 大整數與高精度小數使用 BigInteger／BigDecimal 轉換，YAML 安全載入不建立任意 Java 類別、拒絕 duplicate keys 與 aliases。
- JSON／YAML 整份檔案上限 16 MiB。SQLite 不使用這個檔案上限，但 snapshot 與搬遷仍會將所有資料項目載入記憶體；大型 region entity repository 不能拿單一巨大資料項目全量重寫，也不能宣稱已具備空間索引或查詢分頁。`saveAll` 是同一儲存庫的批次寫入，不是跨插件交易。
- API 是同步的。可以用於必須先提交再進行下一步的維護 journal，不能在每次移動、聊天、方塊事件等熱路徑做 I/O。呼叫方安排背景讀取與主執行緒遊戲狀態更新。

**不提供跨儲存庫、跨插件或資料庫加世界資料夾的原子交易 transaction。** 檔案 force 不代表已完成磁碟控制器、目錄 metadata 與停電耐久性的所有驗證；程式崩潰或斷電仍需 journal 與還原流程。file lock 只約束合作使用本 library 的程序，不能阻止外部程式直接改檔。

## 離線搬遷

```text
java -jar output/tokaStorage-<版本>-all.jar migrate JSON /path/source SQLITE /path/new-target 1 /path/backups
```

來源必須已存在對應 backend 資料檔，目標整個目錄不得存在，也不能置於來源內。搬遷取得來源 lock，先保存獨立 JSON snapshot backup 並比對，再寫入新的 staging 目錄、逐筆比對、關閉並重新開啟驗證，最後移到新目標。數字比對使用精確 BigDecimal 語意，不用 double 近似判斷。

失敗不刪除或轉換來源、不覆蓋既有目標。已建立的 backup 與失敗 staging 會保留，staging 有 `MIGRATION_FAILED.txt` 時不得啟用。最後目錄 move 使用拒絕覆蓋的預設語意，**不宣稱檔案系統間切換原子**。備份是本 library 儲存資料的 snapshot，並不是整張世界、原 SQLite binary 或其他插件／設定的完整備份。

正常使用者資料目錄是信任邊界；拒絕直接 symbolic store/data/lock file，不代表抵禦管理員在執行中替換父目錄或特殊檔案的完整沙箱。

## 編譯及測試

Java 21、Maven，執行 `./build.ps1`；Maven 不在 PATH 時提供 `-Maven`，依賴快取可提供 `-Repository`。預設使用同層 plugin-modernization/build/m2，減少重複下載。建置不跳過測試，`mvn clean install` 將薄及 all artifact 安裝到指定 repository，再複製到 output；output、target 與測試資料不進版控。

測試覆蓋三種 backend 重開讀寫、九種格式互轉、精確數字與中文/null/巢狀資料、來源保留、backup 驗證、目的地不覆蓋、target 寫入失敗、lock、schema 不符、錯誤 backend、危險 YAML、損壞 JSON，以及不初始化其他 SQLite 資料庫。測試資料保存在 `.build-tools/tests`，不接正式 MySQL、VPS 或玩家資料。

既有驗證紀錄包含 library 測試與宿主整合測試；測試數量及最新結果以實際建置輸出為準。主線另以打包此 library 的 tokaWorlds 1.0.1，在 Windows 的 PM1E 4511 及測試插件環境驗證三後端載入與世界工作持久化；不能據此宣稱所有插件 classloader 組合都相容。

尚未測 Linux 檔案系統、斷電／process kill、磁碟滿載或正式 VPS。此 prototype 不能直接當完成全部插件資料格式搬遷。

### 0.1.4

- 修正：搬遷工具拒絕空檔案或沒有任何資料記錄的來源，不再視為成功；金鑰檢查改用預編譯規則。API 與資料格式不變。

### 0.1.3

- 修正：SQLite 檔案已存在但是空的（例如第一次開啟被中斷）時，開啟會因為 `no such table: metadata` 失敗；現在視為新儲存庫重新建立。非空且沒有 `metadata` 表的檔案仍會拒絕開啟。

### 0.1.2
新增選用的隔離 SQLite driver 載入：宿主若提供 META-INF/toka/sqlite-jdbc.jar，使用僅繼承 Java platform 的獨立 classloader，保留 JNI 套件名稱。未提供時沿用宿主 classloader。tokaWorlds 1.0.3 已採用此模式；其他宿主須自行配置打包才能取得隔離效果。資料 schema 不變。
