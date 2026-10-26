import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';

import styles from './index.module.css';

const features = [
  {
    title: (
      <>
        All the <a href="https://ammonite.io">Ammonite</a> niceties
      </>
    ),
    image: 'frontpage/ammonite.gif',
    imageAlt: 'Ammonite features',
    content: (
      <>
        <p>
          Ammonite is a modern and user-friendly Scala shell. Almond wraps it in a Jupyter kernel,
          giving you all its features and niceties, including customizable pretty-printing, magic
          imports, advanced dependency handling, its API, right from Jupyter.
        </p>
        <p>
          This also makes it easy to copy some code from notebooks to Ammonite scripts, and vice
          versa.
        </p>
      </>
    ),
  },
  {
    title: 'APIs to interact with Jupyter front-ends',
    image: 'frontpage/progressbar.gif',
    imageAlt: 'Progress bar',
    content: (
      <p>
        Almond exposes APIs to interact with Jupyter front-ends. Call them from notebooks… or from
        your own libraries.
      </p>
    ),
  },
  {
    title: 'Plotting libraries',
    image: 'frontpage/plotly.gif',
    imageAlt: 'Plots',
    content: (
      <p>
        Several plotting libraries are already available to plot things from notebooks, such
        as <a href="http://plotly-scala.org">plotly-scala</a> or{' '}
        <a href="https://github.com/vegas-viz/Vegas">Vegas</a>.
      </p>
    ),
  },
  {
    title: 'Spark support',
    image: 'frontpage/spark.gif',
    imageAlt: 'Spark',
    content: (
      <p>
        Load the spark version of your choice, create a Spark session, and start using it from your
        notebooks.
      </p>
    ),
  },
  {
    title: 'IDE-like features',
    image: 'frontpage/metabrowse.gif',
    imageAlt: 'Metabrowse',
    content: (
      <p>
        Almond already supports code navigation in dependencies
        via <a href="https://github.com/scalameta/metabrowse">metabrowse</a>, paving the way for
        more IDE-like features and a closer integration with
        the <a href="https://github.com/scalameta">scalameta</a> ecosystem.
      </p>
    ),
  },
  {
    title: 'Custom kernels',
    image: 'frontpage/echo.gif',
    imageAlt: 'Echo kernel',
    content: (
      <p>
        Write Jupyter kernels for the language of your choice, in Scala, by relying on the exact
        same libraries as Almond.
      </p>
    ),
  },
];

function HomeSplash() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <header className={styles.splash}>
      <div className="container">
        <img
          className={styles.logo}
          src={useBaseUrl('logos/impure-logos-almond-0.svg')}
          alt="almond logo"
        />
        <h1 className={styles.title}>{siteConfig.title}</h1>
        <p className={styles.tagline}>{siteConfig.tagline}</p>
        <div className={styles.buttons}>
          <Link
            className="button button--primary button--lg"
            href="https://mybinder.org/v2/gh/almond-sh/examples/master?urlpath=lab%2Ftree%2Fnotebooks%2Findex.ipynb">
            Try it online
          </Link>
          <Link className="button button--primary button--lg" to="/docs/try-docker">
            Try it with docker
          </Link>
          <Link className="button button--primary button--lg" to="/docs/quick-start-install">
            Install
          </Link>
        </div>
      </div>
    </header>
  );
}

function Feature({title, image, imageAlt, content, imageLeft}) {
  return (
    <section className={styles.feature}>
      <div className="container">
        <div className={clsx('row', styles.featureRow, imageLeft && styles.featureRowReverse)}>
          <div className="col col--6">
            <h2>{title}</h2>
            {content}
          </div>
          <div className="col col--6">
            <img className={styles.featureImage} src={useBaseUrl(image)} alt={imageAlt} />
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout description={siteConfig.tagline}>
      <HomeSplash />
      <main>
        {features.map((feature, idx) => (
          <Feature key={idx} imageLeft={idx % 2 === 1} {...feature} />
        ))}
      </main>
    </Layout>
  );
}
