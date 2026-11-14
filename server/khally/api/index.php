<?php
/**
 * خلي بالك من ابنك — API
 * كل الطلبات: index.php?action=...
 * التوثيق: هيدر X-Token (توكن ولي الأمر أو توكن جهاز الابن)
 */
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

const CODE_TTL_MS   = 15 * 60 * 1000;   // صلاحية كود الربط
const LIVE_TTL_MS   = 15 * 1000;        // البث يقف لو ولي الأمر قفل الشاشة
const LONG_POLL_SEC = 20;               // جهاز الابن ينتظر التغييرات حتى 20 ثانية
const MAX_FRAME     = 400 * 1024;

$DEFAULT_POLICY = [
    'rules' => [], 'blockInstallUninstall' => true, 'blockSettings' => true,
    'lockDevice' => false, 'bedtimeStart' => '', 'bedtimeEnd' => '',
];

// ───────────── أدوات ─────────────
function out(array $d, int $code = 200): never {
    http_response_code($code);
    echo json_encode($d, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}
function fail(string $msg, int $code = 400): never { out(['error' => $msg], $code); }
function body(): array {
    static $b = null;
    if ($b === null) {
        $b = json_decode(file_get_contents('php://input') ?: '[]', true);
        if (!is_array($b)) $b = [];
    }
    return $b;
}
function nowMs(): int { return (int) floor(microtime(true) * 1000); }
function newToken(): string { return bin2hex(random_bytes(32)); }
function token(): string {
    $t = $_SERVER['HTTP_X_TOKEN'] ?? '';
    return preg_match('/^[a-f0-9]{64}$/', $t) ? $t : '';
}
function str(mixed $v, int $max): string { return mb_substr(trim((string) $v), 0, $max); }

$cfg = require __DIR__ . '/config.php';
if (empty($cfg['installed'])) {
    if (($_GET['action'] ?? '') === 'ping') out(['ok' => false, 'app' => 'khally', 'installed' => false]);
    fail('السيرفر لم يتم تثبيته بعد — افتح khally/install.php من المتصفح', 503);
}
try {
    $pdo = new PDO(
        "mysql:host={$cfg['db_host']};dbname={$cfg['db_name']};charset=utf8mb4",
        $cfg['db_user'], $cfg['db_pass'],
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
         PDO::ATTR_EMULATE_PREPARES => false]
    );
} catch (Throwable $e) {
    fail('تعذر الاتصال بقاعدة البيانات', 500);
}

function q(string $sql, array $args = []): PDOStatement {
    global $pdo;
    $s = $pdo->prepare($sql);
    $s->execute($args);
    return $s;
}

function parentAuth(): int {
    $t = token() ?: fail('سجّل الدخول من جديد', 401);
    $row = q('SELECT parent_id FROM parent_sessions WHERE token_hash=?', [hash('sha256', $t)])->fetch();
    return $row ? (int) $row['parent_id'] : fail('سجّل الدخول من جديد', 401);
}
function childAuth(): array {
    $t = token() ?: fail('الجهاز غير مربوط', 401);
    $row = q('SELECT * FROM children WHERE token_hash=?', [hash('sha256', $t)])->fetch();
    return $row ?: fail('الجهاز غير مربوط', 401);
}
function ownedChild(int $parentId, string $id): array {
    $row = q('SELECT * FROM children WHERE id=? AND parent_id=?', [$id, $parentId])->fetch();
    return $row ?: fail('الجهاز غير موجود', 404);
}
function childJson(array $r): array {
    return [
        'id' => $r['id'], 'name' => $r['name'], 'model' => $r['model'],
        'lastSeen' => (int) $r['last_seen'], 'battery' => (int) $r['battery'],
        'currentApp' => $r['current_app'],
        'liveActive' => (int) $r['live_until'] > nowMs(),
        'lockNowRequest' => (int) $r['lock_now_request'],
        'alert' => $r['alert'], 'alertAt' => (int) $r['alert_at'],
        'policy' => json_decode($r['policy'], true) ?: new stdClass(),
        'apps' => json_decode($r['apps'], true) ?: [],
        'version' => (int) $r['version'],
    ];
}
function bump(string $id): void { q('UPDATE children SET version=version+1 WHERE id=?', [$id]); }

function tooManyLogins(): bool {
    $ip = $_SERVER['REMOTE_ADDR'] ?? '';
    q('DELETE FROM login_attempts WHERE at < ?', [nowMs() - 15 * 60 * 1000]);
    $n = (int) q('SELECT COUNT(*) c FROM login_attempts WHERE ip=?', [$ip])->fetch()['c'];
    return $n >= 10;
}
function recordFailedLogin(): void {
    q('INSERT INTO login_attempts (ip, at) VALUES (?, ?)', [$_SERVER['REMOTE_ADDR'] ?? '', nowMs()]);
}

// ───────────── المسارات ─────────────
$action = $_GET['action'] ?? '';

switch ($action) {

    // ===== ولي الأمر =====
    case 'register': {
        $email = strtolower(str(body()['email'] ?? '', 190));
        $pass = (string) (body()['password'] ?? '');
        if (!filter_var($email, FILTER_VALIDATE_EMAIL)) fail('البريد الإلكتروني غير صحيح');
        if (strlen($pass) < 6) fail('كلمة المرور لازم تكون 6 أحرف على الأقل');
        if (q('SELECT id FROM parents WHERE email=?', [$email])->fetch()) fail('البريد ده مسجل قبل كده، سجّل دخول');
        q('INSERT INTO parents (email, pass_hash, created_at) VALUES (?,?,?)',
            [$email, password_hash($pass, PASSWORD_DEFAULT), nowMs()]);
        $t = newToken();
        q('INSERT INTO parent_sessions VALUES (?,?,?)', [hash('sha256', $t), (int) $pdo->lastInsertId(), nowMs()]);
        out(['token' => $t]);
    }

    case 'login': {
        if (tooManyLogins()) fail('محاولات كتير، جرّب بعد ربع ساعة', 429);
        $email = strtolower(str(body()['email'] ?? '', 190));
        $row = q('SELECT id, pass_hash FROM parents WHERE email=?', [$email])->fetch();
        if (!$row || !password_verify((string) (body()['password'] ?? ''), $row['pass_hash'])) {
            recordFailedLogin();
            fail('البريد أو كلمة المرور غلط', 401);
        }
        $t = newToken();
        q('INSERT INTO parent_sessions VALUES (?,?,?)', [hash('sha256', $t), (int) $row['id'], nowMs()]);
        out(['token' => $t]);
    }

    case 'logout': {
        q('DELETE FROM parent_sessions WHERE token_hash=?', [hash('sha256', token())]);
        out(['ok' => true]);
    }

    case 'children': {
        $pid = parentAuth();
        $rows = q('SELECT * FROM children WHERE parent_id=? ORDER BY name', [$pid])->fetchAll();
        out(['children' => array_map('childJson', $rows)]);
    }

    case 'child': {
        $pid = parentAuth();
        out(['child' => childJson(ownedChild($pid, (string) ($_GET['id'] ?? '')))]);
    }

    case 'create_code': {
        $pid = parentAuth();
        $name = str(body()['name'] ?? '', 100) ?: fail('اكتب اسم الابن');
        q('DELETE FROM pair_codes WHERE created_at < ?', [nowMs() - CODE_TTL_MS]);
        $alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
        $code = '';
        for ($i = 0; $i < 8; $i++) $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        q('INSERT INTO pair_codes VALUES (?,?,?,?)', [$code, $pid, $name, nowMs()]);
        out(['code' => $code]);
    }

    case 'update_policy': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        $p = body()['policy'] ?? null;
        if (!is_array($p)) fail('بيانات غير صحيحة');
        $policy = array_merge($DEFAULT_POLICY, array_intersect_key($p, $DEFAULT_POLICY));
        q('UPDATE children SET policy=?, version=version+1 WHERE id=?',
            [json_encode($policy, JSON_UNESCAPED_UNICODE), $c['id']]);
        out(['ok' => true]);
    }

    case 'lock_now': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        q('UPDATE children SET lock_now_request=?, version=version+1 WHERE id=?', [nowMs(), $c['id']]);
        out(['ok' => true]);
    }

    case 'clear_alert': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        q("UPDATE children SET alert='', alert_at=0 WHERE id=?", [$c['id']]);
        out(['ok' => true]);
    }

    case 'remove_child': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        q('DELETE FROM children WHERE id=?', [$c['id']]);
        out(['ok' => true]);
    }

    // ولي الأمر يطلب آخر إطار — وده نفسه بيشغّل/يمدّد البث
    case 'live_frame': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        $wasActive = (int) $c['live_until'] > nowMs();
        q('UPDATE children SET live_until=? WHERE id=?', [nowMs() + LIVE_TTL_MS, $c['id']]);
        if (!$wasActive) bump($c['id']);
        $since = (int) ($_GET['since'] ?? 0);
        $f = q('SELECT frame, ts FROM live_frames WHERE child_id=?', [$c['id']])->fetch();
        if (!$f || (int) $f['ts'] <= $since) out(['ts' => $f ? (int) $f['ts'] : 0, 'frame' => '']);
        out(['ts' => (int) $f['ts'], 'frame' => base64_encode($f['frame'])]);
    }

    case 'stop_live': {
        $pid = parentAuth();
        $c = ownedChild($pid, (string) ($_GET['id'] ?? ''));
        q('UPDATE children SET live_until=0, version=version+1 WHERE id=?', [$c['id']]);
        out(['ok' => true]);
    }

    // ===== جهاز الابن =====
    case 'pair': {
        $code = strtoupper(str(body()['code'] ?? '', 8));
        $row = q('SELECT * FROM pair_codes WHERE code=? AND created_at > ?', [$code, nowMs() - CODE_TTL_MS])->fetch();
        if (!$row) fail('الكود غلط أو انتهت صلاحيته', 404);
        $id = bin2hex(random_bytes(8));
        $t = newToken();
        q('INSERT INTO children (id, parent_id, token_hash, name, model, last_seen, policy, apps, created_at)
           VALUES (?,?,?,?,?,?,?,?,?)', [
            $id, (int) $row['parent_id'], hash('sha256', $t), $row['child_name'],
            str(body()['model'] ?? '', 100), nowMs(),
            json_encode($DEFAULT_POLICY), '[]', nowMs(),
        ]);
        q('DELETE FROM pair_codes WHERE code=?', [$code]);
        out(['token' => $t, 'id' => $id]);
    }

    // Long-poll: يرجع فوراً لو فيه تغيير، أو ينتظر لحد 20 ثانية
    case 'state': {
        set_time_limit(LONG_POLL_SEC + 15);
        $c = childAuth();
        q('UPDATE children SET last_seen=?, battery=?, current_app=? WHERE id=?', [
            nowMs(), (int) ($_GET['battery'] ?? -1), str($_GET['current_app'] ?? '', 255), $c['id'],
        ]);
        $since = (int) ($_GET['since'] ?? 0);
        $deadline = time() + LONG_POLL_SEC;
        while ((int) $c['version'] <= $since && time() < $deadline) {
            sleep(1);
            $v = q('SELECT version FROM children WHERE id=?', [$c['id']])->fetch();
            if (!$v) fail('الجهاز غير مربوط', 401);
            if ((int) $v['version'] > $since) { $c = childAuth(); break; }
        }
        out(['child' => childJson($c)]);
    }

    case 'apps': {
        $c = childAuth();
        $apps = body()['apps'] ?? [];
        if (!is_array($apps)) fail('بيانات غير صحيحة');
        $clean = [];
        foreach (array_slice($apps, 0, 500) as $a) {
            $clean[] = [
                'pkg' => str($a['pkg'] ?? '', 255), 'label' => str($a['label'] ?? '', 100),
                'usageMin' => (int) ($a['usageMin'] ?? 0),
            ];
        }
        q('UPDATE children SET apps=?, last_seen=? WHERE id=?', [json_encode($clean, JSON_UNESCAPED_UNICODE), nowMs(), $c['id']]);
        out(['ok' => true]);
    }

    case 'alert': {
        $c = childAuth();
        q('UPDATE children SET alert=?, alert_at=? WHERE id=?', [str(body()['alert'] ?? '', 255), nowMs(), $c['id']]);
        out(['ok' => true]);
    }

    // إطار JPEG خام في جسم الطلب
    case 'frame': {
        $c = childAuth();
        $data = file_get_contents('php://input') ?: '';
        if (strlen($data) < 100 || strlen($data) > MAX_FRAME || substr($data, 0, 2) !== "\xFF\xD8") fail('إطار غير صالح');
        q('INSERT INTO live_frames (child_id, frame, ts) VALUES (?,?,?)
           ON DUPLICATE KEY UPDATE frame=VALUES(frame), ts=VALUES(ts)', [$c['id'], $data, nowMs()]);
        out(['live' => (int) $c['live_until'] > nowMs()]);
    }

    case 'ping':
        out(['ok' => true, 'app' => 'khally', 'installed' => true, 'time' => nowMs()]);

    default:
        fail('طلب غير معروف', 404);
}
