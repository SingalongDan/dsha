/**
 * Android 兼容层（preload，与 dns-fix.js 同一机制）。
 *
 * 背景：新版引擎的 `@deepseek-ai/node-addon-system` 提供 POSIX flock（会话持久化要用），
 * 其入口有一句平台判断：
 *
 *     if (platform !== 'linux' && platform !== 'darwin') throw `flock is not supported on ${platform}-${arch}`
 *
 * 而 Android 上 `process.platform === 'android'` → 引擎在**第一轮就报错退出**
 * （实测 turn/end reason = {"kind":"error","error":{"message":"flock is not supported on android-arm64"}}）。
 *
 * Termux 的 bionic 前缀在系统调用层面就是 Linux：flock(2) 在 Android 上可用。
 * 这里把 platform 报告为 'linux'，让引擎按其 Linux 分支走 —— 平台名之外的行为不变。
 * 之所以用 preload 而不是改引擎源码：与 patchelf 重定位、dns-fix 一样属于"宿主适配层"，
 * 升级引擎时无需重新打补丁。
 */
try {
    Object.defineProperty(process, 'platform', {
        value: 'linux',
        enumerable: true,
        writable: false,
        configurable: true,
    });
    // bionic 没有 glibcVersionRuntime，node-addon-system 会据此选 musl/ 目录下的原生件；
    // 我们在宿主侧把 android-arm64 的原生件同时放到 musl/ 与 glibc/ 两个路径下（见打包脚本）。
} catch (e) {
    // 平台名改不动也不致命：真出错时引擎会给出明确报错
}
