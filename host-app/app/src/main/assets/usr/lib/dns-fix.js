// 绕过坏掉的系统 DNS：把 dns.lookup 覆盖成走 c-ares（公共 DNS）。
// IP 字面量（如 127.0.0.1）直接返回，不走 DNS。
const dns = require('dns');
const net = require('net');
dns.setServers(['223.5.5.5', '119.29.29.29']);

dns.lookup = function (hostname, options, callback) {
  if (typeof options === 'function') { callback = options; options = {}; }
  options = options || {};
  const all = options.all === true;
  const family = options.family;

  // IP 字面量直接返回
  const ipVer = net.isIP(hostname);
  if (ipVer) {
    if (all) return callback(null, [{ address: hostname, family: ipVer }]);
    return callback(null, hostname, ipVer);
  }

  const resolve = family === 6 ? dns.resolve6 : dns.resolve4;
  resolve.call(dns, hostname, (err, addrs) => {
    if (err) return callback(err);
    if (all) return callback(null, addrs.map((a) => ({ address: a, family: family === 6 ? 6 : 4 })));
    callback(null, addrs[0], family === 6 ? 6 : 4);
  });
};
