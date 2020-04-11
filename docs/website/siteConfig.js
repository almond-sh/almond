// See https://docusaurus.io/docs/site-config for all the possible
// site configuration options.

const siteConfig = {
  algolia: {
    apiKey: '2cf058a89a451066357a2eece168e7af',
    indexName: 'almond',
  },
  
  title: 'almond',
  tagline: 'A Scala kernel for Jupyter',

  // wiped when relativizing stuff
  url: 'https://almond-sh.github.io',
  baseUrl: '/almond/',

  projectName: 'almond',
  organizationName: 'almond-sh',

  customDocsPath: 'processed-pages',

  headerLinks: [
    {doc: 'intro', label: 'Docs'},
    {blog: true, label: 'Blog'},
    {href: 'https://github.com/almond-sh/almond', label: 'GitHub'},
  ],

  users: [],

  headerIcon: 'logos/impure-logos-almond-0.svg',

  colors: {
    primaryColor: '#39808a',
    secondaryColor: '#69b5c0',
  },

  copyright: `Copyright © ${new Date().getFullYear()} almond contributors`,

  highlight: {
    theme: 'default',
  },

  scripts: ['https://buttons.github.io/buttons.js'],

  onPageNav: 'separate',
  cleanUrl: true,

  enableUpdateTime: true, // doesn't seem to work

  editUrl: 'https://github.com/almond-sh/almond/edit/master/docs/pages/',
};

module.exports = siteConfig;
