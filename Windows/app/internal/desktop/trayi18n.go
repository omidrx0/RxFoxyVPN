package desktop

import "rxvpn/internal/locale"

// trayTexts are the tray menu labels and notifications, one table per language.
var trayTexts = map[string]map[string]string{
	"fa": {
		"open": "باز کردن RXFOXYVPN", "connect": "اتصال", "disconnect": "قطع اتصال",
		"connection_mode": "حالت اتصال", "mode_tun": "تونل سراسری (TUN)",
		"mode_system": "پروکسی سیستم", "mode_manual": "پروکسی دستی",
		"language": "زبان", "quit": "خروج", "st_on": "متصل", "st_off": "قطع اتصال",
		"running": "برنامه در حال اجراست", "loading": "در حال دریافت…",
		"need_login": "ابتدا در برنامه وارد حساب خود شوید.", "connected": "اتصال برقرار شد.",
		"disconnected": "اتصال قطع شد.", "no_reply": "پاسخی دریافت نشد", "signed_out": "از حساب خارج شدید.",
	},
	"en": {
		"open": "Open RXFOXYVPN", "connect": "Connect", "disconnect": "Disconnect",
		"connection_mode": "Connection mode", "mode_tun": "Full-device TUN",
		"mode_system": "System proxy", "mode_manual": "Manual proxy",
		"language": "Language", "quit": "Exit", "st_on": "Connected", "st_off": "Disconnected",
		"running": "RXFOXYVPN is running", "loading": "Loading…",
		"need_login": "Please sign in to your account in the app first.", "connected": "Connected.",
		"disconnected": "Disconnected.", "no_reply": "No reply", "signed_out": "Signed out.",
	},
	"ar": {
		"open": "فتح RXFOXYVPN", "connect": "اتصال", "disconnect": "قطع الاتصال",
		"connection_mode": "وضع الاتصال", "mode_tun": "نفق الجهاز بالكامل (TUN)",
		"mode_system": "وكيل النظام", "mode_manual": "وكيل يدوي",
		"language": "اللغة", "quit": "خروج", "st_on": "متصل", "st_off": "غير متصل",
		"running": "تطبيق RXFOXYVPN قيد التشغيل", "loading": "جارٍ التحميل…",
		"need_login": "يرجى تسجيل الدخول إلى حسابك في التطبيق أولاً.", "connected": "تم الاتصال.",
		"disconnected": "تم قطع الاتصال.", "no_reply": "لا توجد استجابة", "signed_out": "تم تسجيل الخروج.",
	},
	"ru": {
		"open": "Открыть RXFOXYVPN", "connect": "Подключиться", "disconnect": "Отключиться",
		"connection_mode": "Режим подключения", "mode_tun": "Полный туннель (TUN)",
		"mode_system": "Системный прокси", "mode_manual": "Ручной прокси",
		"language": "Язык", "quit": "Выход", "st_on": "Подключено", "st_off": "Не подключено",
		"running": "RXFOXYVPN работает", "loading": "Загрузка…",
		"need_login": "Сначала войдите в свою учётную запись в приложении.", "connected": "Подключено.",
		"disconnected": "Соединение отключено.", "no_reply": "Нет ответа", "signed_out": "Вы вышли из аккаунта.",
	},
	"ko": {
		"open": "RXFOXYVPN 열기", "connect": "연결", "disconnect": "연결 해제",
		"connection_mode": "연결 모드", "mode_tun": "전체 기기 TUN",
		"mode_system": "시스템 프록시", "mode_manual": "수동 프록시",
		"language": "언어", "quit": "종료", "st_on": "연결됨", "st_off": "연결 끊김",
		"running": "RXFOXYVPN 실행 중", "loading": "불러오는 중…",
		"need_login": "먼저 앱에서 계정에 로그인하세요.", "connected": "연결되었습니다.",
		"disconnected": "연결이 해제되었습니다.", "no_reply": "응답 없음", "signed_out": "로그아웃되었습니다.",
	},
	"zh": {
		"open": "打开 RXFOXYVPN", "connect": "连接", "disconnect": "断开连接",
		"connection_mode": "连接模式", "mode_tun": "全设备隧道 (TUN)",
		"mode_system": "系统代理", "mode_manual": "手动代理",
		"language": "语言", "quit": "退出", "st_on": "已连接", "st_off": "未连接",
		"running": "RXFOXYVPN 正在运行", "loading": "正在加载…",
		"need_login": "请先在应用中登录您的账户。", "connected": "连接成功。",
		"disconnected": "已断开连接。", "no_reply": "无响应", "signed_out": "已退出登录。",
	},
}

// languageNames are shown in the tray's language submenu, each in its own language.
var languageNames = []struct{ Code, Name string }{
	{"en", "English"}, {"fa", "فارسی"}, {"ar", "العربية"},
	{"ru", "Русский"}, {"zh", "中文"}, {"ko", "한국어"},
}

// tt returns the tray text for key in the current panel language (English as fallback).
func tt(key string) string {
	lang := locale.Get()
	if table, ok := trayTexts[lang]; ok {
		if s, ok := table[key]; ok {
			return s
		}
	}
	if s, ok := trayTexts["en"][key]; ok {
		return s
	}
	return key
}
