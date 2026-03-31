const { createHash } = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');

/**
 * Parse an owner/repository identifier from GitHub Actions environment input.
 *
 * @param {string | undefined} repository
 * @returns {{ owner: string, repo: string }}
 */
function parseRepository(repository) {
  const [owner, repo] = (repository ?? '').split('/');
  if (!owner || !repo) {
    throw new Error(`Invalid action repository '${repository}'.`);
  }
  return { owner, repo };
}

/**
 * Select the required release assets from a GitHub release asset list.
 *
 * @param {{ name: string }[]} assets
 * @returns {{ jarAsset: { name: string, browser_download_url: string }, checksumAsset: { name: string, browser_download_url: string } }}
 */
function getExpectedAssets(assets) {
  const releaseAssets = new Map(assets.map((asset) => [asset.name, asset]));
  const jarAsset = releaseAssets.get('xml-model-validator.jar');
  const checksumAsset = releaseAssets.get('xml-model-validator.jar.sha256');

  if (!jarAsset || !checksumAsset) {
    throw new Error('Release is missing the expected validator jar assets.');
  }

  return { jarAsset, checksumAsset };
}

/**
 * Verify that the downloaded jar matches the published checksum asset.
 *
 * @param {Buffer} jarBytes
 * @param {Buffer} checksumBytes
 * @returns {void}
 */
function verifyChecksum(jarBytes, checksumBytes) {
  const expectedChecksum = checksumBytes.toString('utf8').trim().split(/\s+/)[0];
  const actualChecksum = createHash('sha256').update(jarBytes).digest('hex');

  if (expectedChecksum !== actualChecksum) {
    throw new Error(`Checksum mismatch for xml-model-validator.jar: expected ${expectedChecksum}, got ${actualChecksum}.`);
  }
}

/**
 * Download one binary asset from a GitHub release.
 *
 * @param {{ name: string, browser_download_url: string }} asset
 * @param {(url: string, init: { headers: Record<string, string> }) => Promise<{ ok: boolean, status: number, arrayBuffer(): Promise<ArrayBufferLike> }>} fetchImpl
 * @param {string} token
 * @returns {Promise<Buffer>}
 */
async function downloadAsset(asset, fetchImpl, token) {
  const response = await fetchImpl(asset.browser_download_url, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/octet-stream',
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to download ${asset.name}: HTTP ${response.status}`);
  }

  return Buffer.from(await response.arrayBuffer());
}

/**
 * Fetch the expected validator jar assets for a specific release tag.
 *
 * @param {{ rest: { repos: { getReleaseByTag(params: { owner: string, repo: string, tag: string }): Promise<{ data: { assets: { name: string, browser_download_url: string }[] } }> } } }} github
 * @param {string} repository
 * @param {string} releaseTag
 * @returns {Promise<{ jarAsset: { name: string, browser_download_url: string }, checksumAsset: { name: string, browser_download_url: string } }>}
 */
async function fetchReleaseAssets(github, repository, releaseTag) {
  const { owner, repo } = parseRepository(repository);
  const release = await github.rest.repos.getReleaseByTag({
    owner,
    repo,
    tag: releaseTag,
  });

  return getExpectedAssets(release.data.assets);
}

/**
 * Download, verify, and cache the published validator jar for the resolved release tag.
 *
 * @param {{
 *   github: { rest: { repos: { getReleaseByTag(params: { owner: string, repo: string, tag: string }): Promise<{ data: { assets: { name: string, browser_download_url: string }[] } }> } } },
 *   fetchImpl?: typeof fetch,
 *   env?: NodeJS.ProcessEnv,
 *   fsPromises?: typeof fs,
 *   pathModule?: typeof path,
 * }} [options]
 * @returns {Promise<void>}
 */
async function main({
  github,
  fetchImpl = fetch,
  env = process.env,
  fsPromises = fs,
  pathModule = path,
} = {}) {
  const repository = env.XML_MODEL_VALIDATOR_ACTION_REPOSITORY;
  const releaseTag = env.XML_MODEL_VALIDATOR_RELEASE_TAG;
  const homeDirectory = env.HOME;
  const token = env.GITHUB_TOKEN;

  if (!repository || !releaseTag || !homeDirectory || !token) {
    throw new Error('Missing repository, release tag, home directory, or GitHub token.');
  }

  const jarCacheDirectory = pathModule.join(homeDirectory, '.cache', 'xml-model-validator', 'jar');
  const { jarAsset, checksumAsset } = await fetchReleaseAssets(github, repository, releaseTag);
  const [jarBytes, checksumBytes] = await Promise.all([
    downloadAsset(jarAsset, fetchImpl, token),
    downloadAsset(checksumAsset, fetchImpl, token),
  ]);

  verifyChecksum(jarBytes, checksumBytes);
  await fsPromises.mkdir(jarCacheDirectory, { recursive: true });
  await fsPromises.writeFile(pathModule.join(jarCacheDirectory, 'xml-model-validator.jar'), jarBytes);
}

module.exports = {
  downloadAsset,
  fetchReleaseAssets,
  getExpectedAssets,
  main,
  parseRepository,
  verifyChecksum,
};
