/**
 * 验证码 AES-GCM 解密工具
 *
 * 密钥派生：SHA256(UserAgent + timeWindow)
 * 每分钟密钥自动轮换，UA 绑定当前浏览器
 */

/**
 * 当前时间窗口（分钟级）
 */
function currentTimeWindow(): number {
  return Math.floor(Date.now() / 60000);
}

/**
 * SHA-256 哈希
 */
async function sha256(input: string): Promise<ArrayBuffer> {
  const encoder = new TextEncoder();
  const data = encoder.encode(input);
  return crypto.subtle.digest('SHA-256', data);
}

/**
 * 从字符串派生 AES-256-GCM 密钥
 */
async function deriveKey(...parts: string[]): Promise<CryptoKey> {
  const combined = parts.join('');
  const hash = await sha256(combined);
  return crypto.subtle.importKey('raw', hash, 'AES-GCM', false, ['decrypt']);
}

/**
 * AES-GCM 解密
 * @param key AES 密钥
 * @param encryptedBase64 Base64 编码的密文（IV[12] + ciphertext + tag）
 * @returns 解密后的明文字符串
 */
async function aesDecrypt(key: CryptoKey, encryptedBase64: string): Promise<string> {
  // Base64 → Uint8Array
  const binaryString = atob(encryptedBase64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }

  // 前 12 字节是 IV
  const iv = bytes.slice(0, 12);
  const ciphertext = bytes.slice(12);

  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv },
    key,
    ciphertext
  );

  return new TextDecoder().decode(plaintext);
}

export interface CaptchaItem {
  index: number;
  shape: string;
  color: string;
  size: string;
  colorHex: string;
  isTarget: boolean;
}

export interface CaptchaData {
  captchaId: string;
  hint: string;
  items: CaptchaItem[];
}

/**
 * 解密验证码数据
 * 尝试当前时间窗口 ±1 分钟（覆盖时钟偏差）
 *
 * @param encrypted Base64 加密数据
 * @param captchaId 验证码 ID（用于日志）
 * @returns 解密后的验证码数据
 */
export async function decryptCaptcha(encrypted: string, captchaId: string): Promise<CaptchaData> {
  // 检查 Web Crypto API 是否可用（手机浏览器通过 HTTP 访问时不可用）
  if (!window.crypto?.subtle) {
    throw new Error('当前浏览器不支持安全加密，请使用 HTTPS 访问或更换浏览器');
  }

  const ua = navigator.userAgent;
  const now = currentTimeWindow();

  // 尝试当前分钟 ±1（覆盖时钟偏差）
  for (const offset of [0, -1, +1]) {
    const timeWindow = now + offset;
    try {
      const key = await deriveKey(ua, String(timeWindow));
      const plaintext = await aesDecrypt(key, encrypted);
      const data = JSON.parse(plaintext) as CaptchaData;
      console.debug(`[captcha] 解密成功, timeWindow=${timeWindow}, captchaId=${captchaId}`);
      return data;
    } catch {
      // 解密失败，试下一个时间窗口
      continue;
    }
  }

  throw new Error('验证码解密失败，请刷新重试');
}
