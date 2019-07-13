const React = require('react');

const CompLibrary = require('../../core/CompLibrary.js');
const MarkdownBlock = CompLibrary.MarkdownBlock; /* Used to read markdown */
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

const siteConfig = require(process.cwd() + '/siteConfig.js');

class Button extends React.Component {
  render() {
    return (
      <div className="pluginWrapper buttonWrapper">
        <a className="button" href={this.props.href} target={this.props.target}>
          {this.props.children}
        </a>
      </div>
    );
  }
}

function assetUrl(img) {
  return siteConfig.baseUrl + 'docs/assets/' + img;
}

function docUrl(doc, language) {
  return siteConfig.baseUrl + 'docs/' + (language ? language + '/' : '') + doc;
}

Button.defaultProps = {
  target: '_self',
};

const SplashContainer = props => (
  <div className="homeContainer">
    <div className="homeSplashFade">
      <div className="wrapper homeWrapper">{props.children}</div>
    </div>
  </div>
);

const Logo = props => (
  <div className="projectLogo">
    <img src={props.img_src} alt="Project Logo" />
  </div>
);

const ProjectTitle = props => (
  <h2 className="projectTitle">
    {siteConfig.title}
    <small>{siteConfig.tagline}</small>
  </h2>
);

const PromoSection = props => (
  <div className="section promoSection">
    <div className="promoRow">
      <div className="pluginRowBlock">{props.children}</div>
    </div>
  </div>
);

class HomeSplash extends React.Component {
  render() {
    let language = this.props.language || '';
    return (
      <SplashContainer>
        <Logo img_src={`${siteConfig.baseUrl}logos/impure-logos-almond-0.svg`} />
        <div className="inner">
          <ProjectTitle />
          <PromoSection>
            <Button href="https://mybinder.org/v2/gh/almond-sh/examples/master?urlpath=lab%2Ftree%2Fnotebooks%2Findex.ipynb">Try it online</Button>
            <Button href={docUrl('try-docker', language)}>Try it with docker</Button>
            <Button href={docUrl('quick-start-install', language)}>Install</Button>
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

const Block = props => (
  <Container
      padding={['bottom', 'top']}
      id={props.id}
      background={props.background}>
      <GridBlock align="center" contents={props.children} layout={props.layout}/>
  </Container>
);

class Index extends React.Component {
  render() {
    let language = this.props.language || '';

    return (
        <div>
          <HomeSplash language={language} />
          <div className="mainContainer">
	    <Container padding={['bottom', 'top']}>
              <GridBlock
                contents={[
                  {
                    content: 'Ammonite is a modern and user-friendly Scala shell. Almond wraps it in a Jupyter kernel, giving  you all its features and niceties, including customizable pretty-printing, magic imports, advanced dependency handling, its API, right from Jupyter.\n\nThis also makes it easy to copy some code from notebooks to Ammonite scripts, and vice versa.',
                    imageAlign: 'right',
                    image: `${siteConfig.baseUrl}frontpage/ammonite.gif`,
                    imageAlt: 'Ammonite features',
                    title: 'All the <a href="https://ammonite.io">Ammonite</a> niceties',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
	    <Container padding={['bottom', 'top']} background="light">
              <GridBlock
                contents={[
                  {
                    content: 'Almond exposes APIs to interact with Jupyter front-ends. Call them from notebooks… or from your own libraries.',
                    imageAlign: 'left',
                    image: `${siteConfig.baseUrl}frontpage/progressbar.gif`,
                    imageAlt: 'Progress bar',
                    title: 'APIs to interact with Jupyter front-ends',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
	    <Container padding={['bottom', 'top']}>
              <GridBlock
                contents={[
                  {
                    content: 'Several plotting libraries are already available to plot things from notebooks, such as <a href="http://plotly-scala.org">plotly-scala</a> or <a href="https://github.com/vegas-viz/Vegas">Vegas</a>.',
                    imageAlign: 'right',
                    image: `${siteConfig.baseUrl}frontpage/plotly.gif`,
                    imageAlt: 'Plots',
                    title: 'Plotting libraries',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
	    <Container padding={['bottom', 'top']}>
              <GridBlock
                contents={[
                  {
                    content: 'Load the spark version of your choice, create a Spark session, and start using it from your notebooks.',
                    imageAlign: 'left',
                    image: `${siteConfig.baseUrl}frontpage/spark.gif`,
                    imageAlt: 'Spark',
                    title: 'Spark support',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
	    <Container padding={['bottom', 'top']}>
              <GridBlock
                contents={[
                  {
                    content: 'Almond already supports code navigation in dependencies via <a href="https://github.com/scalameta/metabrowse">metabrowse</a>, paving the way for more IDE-like features and a closer integration with the <a href="https://github.com/scalameta">scalameta</a> ecosystem.',
                    imageAlign: 'right',
                    image: `${siteConfig.baseUrl}frontpage/metabrowse.gif`,
                    imageAlt: 'Metabrowse',
                    title: 'IDE-like features',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
	    <Container padding={['bottom', 'top']}>
              <GridBlock
                contents={[
                  {
                    content: 'Write Jupyter kernels for the language of your choice, in Scala, by relying on the exact same libraries as Almond.',
                    imageAlign: 'left',
                    image: `${siteConfig.baseUrl}frontpage/echo.gif`,
                    imageAlt: 'Echo kernel',
                    title: 'Custom kernels',
                  },
                ]}
                layout="twoColumn"
              />
            </Container>
          </div>
        </div>
    );
  }
}

module.exports = Index;
