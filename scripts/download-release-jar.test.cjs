const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const {
  getExpectedAssets,
  main,
  parseRepository,
  verifyChecksum,
} = require('./download-release-jar.cjs');

test('parseRepository rejects invalid repository names', () => {
  assert.throws(() => parseRepository('xml-model-validator'));
});

test('getExpectedAssets requires both release assets', () => {
  assert.throws(() => getExpectedAssets([{ name: 'xml-model-validator.jar' }]));
});

test('verifyChecksum rejects mismatched checksums', () => {
  assert.throws(() => verifyChecksum(Buffer.from('jar'), Buffer.from('deadbeef  xml-model-validator.jar\n')));
});

test('main downloads, verifies, and writes the release jar', async () => {
  const temporaryHome = await fs.mkdtemp(path.join(os.tmpdir(), 'xml-model-validator-home-'));
  const jarBytes = Buffer.from('validator jar');
  const checksum = createHash('sha256').update(jarBytes).digest('hex');

  const github = {
    rest: {
      repos: {
        async getReleaseByTag() {
          return {
            data: {
              assets: [
                {
                  name: 'xml-model-validator.jar',
                  browser_download_url: 'https://example.invalid/jar',
                },
                {
                  name: 'xml-model-validator.jar.sha256',
                  browser_download_url: 'https://example.invalid/checksum',
                },
              ],
            },
          };
        },
      },
    },
  };

  const fetchCalls = [];
  const fetchImpl = async (url) => {
    fetchCalls.push(url);
    if (url.endsWith('/jar')) {
      return {
        ok: true,
        async arrayBuffer() {
          return jarBytes;
        },
      };
    }
    return {
      ok: true,
      async arrayBuffer() {
        return Buffer.from(`${checksum}  xml-model-validator.jar\n`);
      },
    };
  };

  await main({
    github,
    fetchImpl,
    env: {
      GITHUB_TOKEN: 'token',
      HOME: temporaryHome,
      XML_MODEL_VALIDATOR_ACTION_REPOSITORY: 'adunning/xml-model-validator',
      XML_MODEL_VALIDATOR_RELEASE_TAG: 'v2.1.0',
    },
  });

  const jarPath = path.join(temporaryHome, '.cache', 'xml-model-validator', 'jar', 'xml-model-validator.jar');
  assert.deepEqual(fetchCalls, ['https://example.invalid/jar', 'https://example.invalid/checksum']);
  assert.equal(await fs.readFile(jarPath, 'utf8'), 'validator jar');
});
