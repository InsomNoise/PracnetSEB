<?php
/**
 * Pracnet SEB - Violation Logger & Auto-Ban
 * 
 * Letakkan file ini di root Moodle: /var/www/html/moodle/seb-violation.php
 * 
 * Endpoint:
 *   POST /seb-violation.php   → Catat pelanggaran
 *   GET  /seb-violation.php   → Lihat semua log (untuk dosen)
 *   GET  /seb-violation.php?check=1&student_id=xxx → Cek status ban
 * 
 * Data POST (JSON):
 *   {
 *     "student_id": "username atau NIM",
 *     "device_id": "android device ID",
 *     "violation_type": "app_exit",
 *     "timestamp": "2024-01-01 10:00:00"
 *   }
 */

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST');
header('Access-Control-Allow-Headers: Content-Type');

// Konfigurasi
define('MAX_VIOLATIONS', 3);          // Batas pelanggaran sebelum ban
define('LOG_FILE', __DIR__ . '/seb-violations.json');

// Inisialisasi file log jika belum ada
if (!file_exists(LOG_FILE)) {
    file_put_contents(LOG_FILE, json_encode([], JSON_PRETTY_PRINT));
}

// Baca log yang ada
function getLog() {
    $content = file_get_contents(LOG_FILE);
    return json_decode($content, true) ?: [];
}

// Simpan log
function saveLog($data) {
    file_put_contents(LOG_FILE, json_encode($data, JSON_PRETTY_PRINT));
}

// Hitung pelanggaran per student
function countViolations($studentId) {
    $log = getLog();
    $count = 0;
    foreach ($log as $entry) {
        if ($entry['student_id'] === $studentId) {
            $count++;
        }
    }
    return $count;
}

// Cek apakah student di-ban
function isBanned($studentId) {
    return countViolations($studentId) >= MAX_VIOLATIONS;
}

// Handle request
$method = $_SERVER['REQUEST_METHOD'];

if ($method === 'POST') {
    // Terima dan catat pelanggaran
    $input = json_decode(file_get_contents('php://input'), true);
    
    if (!$input || empty($input['student_id'])) {
        http_response_code(400);
        echo json_encode(['error' => 'student_id is required']);
        exit;
    }
    
    $entry = [
        'student_id'     => $input['student_id'] ?? 'unknown',
        'device_id'      => $input['device_id'] ?? 'unknown',
        'violation_type' => $input['violation_type'] ?? 'app_exit',
        'timestamp'      => $input['timestamp'] ?? date('Y-m-d H:i:s'),
        'server_time'    => date('Y-m-d H:i:s'),
    ];
    
    $log = getLog();
    $log[] = $entry;
    saveLog($log);
    
    $totalViolations = countViolations($entry['student_id']);
    $banned = $totalViolations >= MAX_VIOLATIONS;
    
    echo json_encode([
        'status'           => 'logged',
        'total_violations' => $totalViolations,
        'max_allowed'      => MAX_VIOLATIONS,
        'banned'           => $banned,
        'message'          => $banned 
            ? 'BANNED: Akses ujian diblokir karena terlalu banyak pelanggaran.' 
            : "Pelanggaran ke-$totalViolations dari " . MAX_VIOLATIONS . " dicatat."
    ]);
    
} elseif ($method === 'GET') {
    
    // Cek status ban untuk student tertentu
    if (isset($_GET['check']) && isset($_GET['student_id'])) {
        $studentId = $_GET['student_id'];
        $violations = countViolations($studentId);
        echo json_encode([
            'student_id'       => $studentId,
            'total_violations' => $violations,
            'max_allowed'      => MAX_VIOLATIONS,
            'banned'           => $violations >= MAX_VIOLATIONS,
        ]);
        exit;
    }
    
    // Tampilkan semua log (untuk dosen/admin)
    // Proteksi sederhana: butuh parameter ?key=admin
    if (!isset($_GET['key']) || $_GET['key'] !== 'admin') {
        http_response_code(403);
        echo json_encode(['error' => 'Unauthorized. Tambahkan ?key=admin']);
        exit;
    }
    
    $log = getLog();
    
    // Hitung summary per student
    $summary = [];
    foreach ($log as $entry) {
        $sid = $entry['student_id'];
        if (!isset($summary[$sid])) {
            $summary[$sid] = [
                'student_id' => $sid,
                'count' => 0,
                'banned' => false,
                'violations' => []
            ];
        }
        $summary[$sid]['count']++;
        $summary[$sid]['banned'] = $summary[$sid]['count'] >= MAX_VIOLATIONS;
        $summary[$sid]['violations'][] = [
            'type' => $entry['violation_type'],
            'time' => $entry['server_time'],
            'device' => $entry['device_id']
        ];
    }
    
    echo json_encode([
        'total_entries' => count($log),
        'students' => array_values($summary)
    ], JSON_PRETTY_PRINT);
    
} else {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed']);
}
