#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');

assert(process.argv.length <= 4, 'Usage: verify-crypto-runtime.cjs [npm-root] [output.json]');
const root = fs.realpathSync(process.argv[2] || path.join(__dirname, '..'));
const runtimeRequire = createRequire(path.join(root, 'package.json'));
const ellipticRequire = createRequire(runtimeRequire.resolve('elliptic'));
const selected = [
  ['elliptic', '6.6.1', runtimeRequire],
  ['bn.js', '4.12.5', ellipticRequire],
  ['js-sha3', '0.8.0', runtimeRequire],
];
const identities = [];
for (const [name, expected, resolver] of selected) {
  const manifest = resolver.resolve(`${name}/package.json`);
  assert.equal(JSON.parse(fs.readFileSync(manifest, 'utf8')).version, expected,
    `unexpected selected ${name} version`);
  const files = [manifest, resolver.resolve(name)];
  if (name === 'elliptic') files.push(resolver.resolve('elliptic/lib/elliptic/ec/index.js'));
  for (const file of files) {
    const relative = path.relative(root, fs.realpathSync(file));
    assert(!relative.startsWith('..') && !path.isAbsolute(relative),
      `crypto dependency resolved outside the selected npm root: ${file}`);
    identities.push({
      package: name,
      version: expected,
      path: relative.split(path.sep).join('/'),
      sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
    });
  }
}

const ec = new (runtimeRequire('elliptic').ec)('secp256k1');
assert.equal(ec.n.bitLength(), 256);
assert.equal(ec.n.byteLength(), 32);
// Synthetic key 1; negative-message rejection is the upstream Critical fix.
assert.throws(() => ec.sign('-01', '01'));
// A zero-prefixed nonce cannot trigger a right shift for this byte-aligned order.
const BN = ellipticRequire('bn.js');
const leadingZeroNonce = new BN(new Uint8Array(32).fill(1).map((value, index) => index ? value : 0));
assert(ec._truncateToN(leadingZeroNonce, true).eq(leadingZeroNonce));

identities.sort((a, b) => a.path.localeCompare(b.path));
if (process.argv[3]) {
  const output = path.resolve(process.argv[3]);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, JSON.stringify(identities, null, 2) + '\n');
}
console.log('Crypto runtime PASS: elliptic 6.6.1, its bn.js 4.12.5, js-sha3 0.8.0; malformed signing rejected');
