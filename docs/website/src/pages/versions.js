import React from 'react';
import Link from '@docusaurus/Link';
import {useVersions, useLatestVersion} from '@docusaurus/plugin-content-docs/client';
import Layout from '@theme/Layout';

const repoUrl = 'https://github.com/almond-sh/almond';

function docLink(version) {
  return version.docs.find((doc) => doc.id === version.mainDocId)?.path ?? version.path;
}

export default function Versions() {
  const versions = useVersions();
  const latestVersion = useLatestVersion();
  const currentVersion = versions.find((version) => version.name === 'current');
  const pastVersions = versions.filter(
    (version) => version !== latestVersion && version.name !== 'current',
  );

  return (
    <Layout title="Versions" description="almond documentation versions">
      <main className="container margin-vert--lg">
        <h1>almond Versions</h1>

        <h2 id="latest">Current version</h2>
        <table>
          <tbody>
            <tr>
              <th>{latestVersion.label}</th>
              <td>
                <Link to={docLink(latestVersion)}>Documentation</Link>
              </td>
              <td>
                <Link href={`${repoUrl}/releases/tag/v${latestVersion.name}`}>Release Notes</Link>
              </td>
            </tr>
          </tbody>
        </table>

        {currentVersion && currentVersion !== latestVersion && (
          <>
            <h2 id="next">Latest Version</h2>
            <p>Here you can find the latest documentation and unreleased code.</p>
            <table>
              <tbody>
                <tr>
                  <th>main</th>
                  <td>
                    <Link to={docLink(currentVersion)}>Documentation</Link>
                  </td>
                  <td>
                    <Link href={repoUrl}>Source Code</Link>
                  </td>
                </tr>
              </tbody>
            </table>
          </>
        )}

        {pastVersions.length > 0 && (
          <>
            <h2 id="archive">Past Versions</h2>
            <p>Here you can find documentation for previous versions.</p>
            <table>
              <tbody>
                {pastVersions.map((version) => (
                  <tr key={version.name}>
                    <th>{version.label}</th>
                    <td>
                      <Link to={docLink(version)}>Documentation</Link>
                    </td>
                    <td>
                      <Link href={`${repoUrl}/releases/tag/v${version.name}`}>Release Notes</Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}

        <p>
          You can find past versions of this project on{' '}
          <Link href={`${repoUrl}/releases`}>GitHub</Link>.
        </p>
      </main>
    </Layout>
  );
}
