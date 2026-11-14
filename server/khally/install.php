<?php
/**
 * خلي بالك من ابنك — المُثبّت التلقائي
 * افتح: https://دومينك/khally/install.php
 */
declare(strict_types=1);
error_reporting(E_ALL);
ini_set('display_errors', '0');

$CONFIG_FILE = __DIR__ . '/api/config.php';
$cfg = is_file($CONFIG_FILE) ? (require $CONFIG_FILE) : [];

$SCHEMA = <<<'SQL'
CREATE TABLE IF NOT EXISTS parents (
  id INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL UNIQUE,
  pass_hash VARCHAR(255) NOT NULL,
  created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS parent_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  parent_id INT NOT NULL,
  created_at BIGINT NOT NULL,
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS children (
  id CHAR(16) PRIMARY KEY,
  parent_id INT NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL DEFAULT '',
  model VARCHAR(100) NOT NULL DEFAULT '',
  last_seen BIGINT NOT NULL DEFAULT 0,
  battery INT NOT NULL DEFAULT -1,
  current_app VARCHAR(255) NOT NULL DEFAULT '',
  live_until BIGINT NOT NULL DEFAULT 0,
  lock_now_request BIGINT NOT NULL DEFAULT 0,
  alert VARCHAR(255) NOT NULL DEFAULT '',
  alert_at BIGINT NOT NULL DEFAULT 0,
  policy MEDIUMTEXT NOT NULL,
  apps MEDIUMTEXT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  INDEX (parent_id),
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS pair_codes (
  code CHAR(8) PRIMARY KEY,
  parent_id INT NOT NULL,
  child_name VARCHAR(100) NOT NULL,
  created_at BIGINT NOT NULL,
  FOREIGN KEY (parent_id) REFERENCES parents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS live_frames (
  child_id CHAR(16) PRIMARY KEY,
  frame MEDIUMBLOB NOT NULL,
  ts BIGINT NOT NULL,
  FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS login_attempts (
  ip VARCHAR(45) NOT NULL,
  at BIGINT NOT NULL,
  INDEX (ip, at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
SQL;

function h(string $s): string { return htmlspecialchars($s, ENT_QUOTES, 'UTF-8'); }

// ───────── عنوان السيرفر اللي هيتكتب في التطبيقات ─────────
$https = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https')
    || (($_SERVER['SERVER_PORT'] ?? '') == 443);
$host = $_SERVER['HTTP_HOST'] ?? 'example.com';
$dir = rtrim(str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? '/khally/install.php')), '/');
$apiUrl = "https://{$host}{$dir}/api/index.php";
$appAddress = ($dir === '/khally') ? $host : "{$host}{$dir}";

// ───────── فحص المتطلبات ─────────
$checks = [
    ['PHP 8.1 أو أحدث (الحالي: ' . PHP_VERSION . ')', version_compare(PHP_VERSION, '8.1.0', '>=')],
    ['إضافة pdo_mysql', extension_loaded('pdo_mysql')],
    ['شهادة SSL (https)', $https],
    ['صلاحية الكتابة على مجلد api', is_writable(__DIR__ . '/api')],
];
$requirementsOk = !in_array(false, array_column($checks, 1), true);

$alreadyInstalled = !empty($cfg['installed']);
$errors = [];
$done = false;
$selfDeleted = false;

if (!$alreadyInstalled && $_SERVER['REQUEST_METHOD'] === 'POST' && $requirementsOk) {
    $dbHost = trim($_POST['db_host'] ?? 'localhost') ?: 'localhost';
    $dbName = trim($_POST['db_name'] ?? '');
    $dbUser = trim($_POST['db_user'] ?? '');
    $dbPass = (string) ($_POST['db_pass'] ?? '');
    $email = strtolower(trim($_POST['email'] ?? ''));
    $pass = (string) ($_POST['password'] ?? '');

    if ($dbName === '' || $dbUser === '') $errors[] = 'اكتب اسم قاعدة البيانات واسم المستخدم';
    if ($email !== '' && !filter_var($email, FILTER_VALIDATE_EMAIL)) $errors[] = 'البريد الإلكتروني غير صحيح';
    if ($email !== '' && strlen($pass) < 6) $errors[] = 'كلمة مرور ولي الأمر لازم 6 أحرف على الأقل';

    if (!$errors) {
        try {
            $pdo = new PDO("mysql:host={$dbHost};dbname={$dbName};charset=utf8mb4", $dbUser, $dbPass,
                [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]);
        } catch (Throwable $e) {
            $errors[] = 'تعذر الاتصال بقاعدة البيانات. تأكد من الاسم الكامل (مثل cpaneluser_khally) وكلمة المرور، '
                . 'وإن المستخدم متضاف للقاعدة بصلاحيات ALL PRIVILEGES.';
            $errors[] = 'تفاصيل: ' . $e->getMessage();
        }
    }

    if (!$errors) {
        try {
            foreach (array_filter(array_map('trim', explode(';', $SCHEMA))) as $stmt) $pdo->exec($stmt);
            if ($email !== '') {
                $s = $pdo->prepare('INSERT INTO parents (email, pass_hash, created_at) VALUES (?,?,?)
                                    ON DUPLICATE KEY UPDATE pass_hash=VALUES(pass_hash)');
                $s->execute([$email, password_hash($pass, PASSWORD_DEFAULT), (int) (microtime(true) * 1000)]);
            }
        } catch (Throwable $e) {
            $errors[] = 'خطأ أثناء إنشاء الجداول: ' . $e->getMessage();
        }
    }

    if (!$errors) {
        $config = "<?php\n// تم إنشاؤه تلقائياً بواسطة المُثبّت في " . date('Y-m-d H:i') . "\nreturn "
            . var_export([
                'installed' => true, 'db_host' => $dbHost, 'db_name' => $dbName,
                'db_user' => $dbUser, 'db_pass' => $dbPass,
            ], true) . ";\n";
        if (@file_put_contents($CONFIG_FILE, $config, LOCK_EX) === false) {
            $errors[] = 'تعذر حفظ ملف الإعدادات api/config.php — تأكد من صلاحيات المجلد (755).';
        } else {
            @chmod($CONFIG_FILE, 0640);
            if (function_exists('opcache_invalidate')) @opcache_invalidate($CONFIG_FILE, true);
            $done = true;
            $selfDeleted = @unlink(__FILE__); // حذف المُثبّت للأمان
        }
    }
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>تثبيت خلي بالك</title>
<style>
  :root { --p:#00796B; --pl:#E0F2F1; --ok:#2E7D32; --err:#C62828; --bg:#F4F7F6; --card:#fff; --tx:#1d2b29; --mut:#5f6f6c; }
  * { box-sizing:border-box }
  body { margin:0; font-family:"Segoe UI",Tahoma,"Noto Kufi Arabic",sans-serif; background:var(--bg); color:var(--tx); line-height:1.7 }
  .wrap { max-width:560px; margin:0 auto; padding:24px 16px 48px }
  header { text-align:center; margin-bottom:20px }
  .logo { width:72px; height:72px; border-radius:20px; background:var(--p); display:inline-grid; place-items:center; color:#fff; font-size:38px }
  h1 { margin:10px 0 2px; font-size:24px }
  .sub { color:var(--mut); margin:0 }
  .card { background:var(--card); border-radius:16px; padding:20px; margin-bottom:16px; box-shadow:0 1px 3px rgba(0,0,0,.08) }
  h2 { font-size:17px; margin:0 0 12px }
  ul.checks { list-style:none; padding:0; margin:0 }
  ul.checks li { padding:6px 0; display:flex; gap:10px; align-items:center }
  .b { width:22px; height:22px; border-radius:50%; display:inline-grid; place-items:center; color:#fff; font-size:13px; flex:none }
  .b.y { background:var(--ok) } .b.n { background:var(--err) }
  label { display:block; font-weight:600; margin:12px 0 4px; font-size:14px }
  .hint { color:var(--mut); font-size:12.5px; font-weight:400 }
  input { width:100%; padding:12px; border:1.5px solid #cfd8d6; border-radius:10px; font-size:16px; direction:ltr; text-align:left }
  input:focus { outline:none; border-color:var(--p) }
  button { width:100%; margin-top:18px; padding:14px; border:0; border-radius:12px; background:var(--p); color:#fff; font-size:17px; font-weight:700; cursor:pointer }
  .alert { border-radius:12px; padding:12px 14px; margin-bottom:16px; font-size:14px }
  .alert.err { background:#FFEBEE; color:var(--err) }
  .alert.ok { background:#E8F5E9; color:var(--ok) }
  .alert.warn { background:#FFF8E1; color:#8D6E00 }
  details { margin-top:4px } summary { cursor:pointer; color:var(--p); font-weight:600 }
  ol { padding-inline-start:20px; margin:8px 0 } code, .addr { direction:ltr; unicode-bidi:embed; font-family:Consolas,monospace }
  .addr { display:block; background:var(--pl); color:var(--p); font-size:22px; font-weight:700; text-align:center; padding:14px; border-radius:12px; margin:8px 0; word-break:break-all }
  hr { border:0; border-top:1px solid #e3e9e8; margin:16px 0 }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">🛡️</div>
    <h1>خلي بالك من ابنك</h1>
    <p class="sub">المُثبّت التلقائي للسيرفر</p>
  </header>

<?php if ($done): ?>
  <div class="card">
    <h2>✅ تم التثبيت بنجاح</h2>
    <p>اكتب العنوان ده في تطبيق ولي الأمر وتطبيق الابن (خانة "عنوان السيرفر"):</p>
    <span class="addr"><?= h($appAddress) ?></span>
    <?php if ($email !== ''): ?>
      <p>وسجّل دخول في تطبيق ولي الأمر بـ <b dir="ltr"><?= h($email) ?></b> وكلمة المرور اللي كتبتها.</p>
    <?php endif; ?>
    <p>اختبار السيرفر: <a href="api/index.php?action=ping" target="_blank">اضغط هنا</a> — لازم يظهر <code>"ok":true</code></p>
  </div>
  <?php if (!$selfDeleted): ?>
    <div class="alert warn">⚠️ مقدرتش أمسح ملف <code>install.php</code> تلقائياً — امسحه بنفسك من File Manager للأمان.</div>
  <?php else: ?>
    <div class="alert ok">🔒 تم حذف ملف المُثبّت تلقائياً للأمان.</div>
  <?php endif; ?>

<?php elseif ($alreadyInstalled): ?>
  <div class="alert ok">السيرفر متثبت بالفعل ✅</div>
  <div class="card">
    <p>عنوان السيرفر للتطبيقات:</p>
    <span class="addr"><?= h($appAddress) ?></span>
    <p class="hint">لإعادة التثبيت: عدّل <code>'installed' =&gt; true</code> إلى <code>false</code> في ملف api/config.php. ويُفضّل حذف install.php.</p>
  </div>

<?php else: ?>
  <div class="card">
    <h2>1) فحص الاستضافة</h2>
    <ul class="checks">
      <?php foreach ($checks as [$label, $ok]): ?>
        <li><span class="b <?= $ok ? 'y' : 'n' ?>"><?= $ok ? '✓' : '✕' ?></span><?= h($label) ?></li>
      <?php endforeach; ?>
    </ul>
    <?php if (!$requirementsOk): ?>
      <hr>
      <div class="hint">
        • PHP و pdo_mysql: من cPanel ← <b>Select PHP Version</b> أو <b>MultiPHP Manager</b>.<br>
        • SSL: من cPanel ← <b>SSL/TLS Status</b> ← <b>Run AutoSSL</b>، وبعدها افتح الصفحة بـ https.<br>
        • الكتابة: من File Manager خلي صلاحيات مجلد <code>khally/api</code> = 755.
      </div>
    <?php endif; ?>
  </div>

  <?php foreach ($errors as $e): ?><div class="alert err"><?= h($e) ?></div><?php endforeach; ?>

  <?php if ($requirementsOk): ?>
  <form method="post" class="card" autocomplete="off">
    <h2>2) قاعدة البيانات</h2>
    <details>
      <summary>إزاي أعمل قاعدة بيانات؟ (دقيقة واحدة)</summary>
      <ol class="hint">
        <li>cPanel ← <b>MySQL Database Wizard</b></li>
        <li>اكتب اسم القاعدة: <code>khally</code> ← Next</li>
        <li>اكتب اسم مستخدم <code>khally</code> وكلمة مرور (اضغط Password Generator واحفظها) ← Create User</li>
        <li>علّم على <b>ALL PRIVILEGES</b> ← Next Step</li>
        <li>الأسماء الكاملة هتبقى بالشكل <code>cpaneluser_khally</code> — اكتبها هنا.</li>
      </ol>
    </details>

    <label>اسم قاعدة البيانات</label>
    <input name="db_name" required placeholder="cpaneluser_khally" value="<?= h($_POST['db_name'] ?? '') ?>">
    <label>اسم المستخدم</label>
    <input name="db_user" required placeholder="cpaneluser_khally" value="<?= h($_POST['db_user'] ?? '') ?>">
    <label>كلمة مرور قاعدة البيانات</label>
    <input name="db_pass" type="password">
    <label>الخادم <span class="hint">(سيبه localhost)</span></label>
    <input name="db_host" value="<?= h($_POST['db_host'] ?? 'localhost') ?>">

    <hr>
    <h2>3) حساب ولي الأمر <span class="hint">(اختياري — ممكن تعمله من التطبيق)</span></h2>
    <label>البريد الإلكتروني</label>
    <input name="email" type="email" value="<?= h($_POST['email'] ?? '') ?>">
    <label>كلمة المرور</label>
    <input name="password" type="password" minlength="6">

    <button type="submit">🚀 ثبّت الآن</button>
  </form>
  <?php endif; ?>
<?php endif; ?>
</div>
</body>
</html>
