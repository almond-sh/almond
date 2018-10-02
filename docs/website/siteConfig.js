// See https://docusaurus.io/docs/site-config for all the possible
// site configuration options.

const siteConfig = {
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
    {href: 'https://github.com/almond-sh/almond', label: 'GitHub'},
  ],

  users: [],

  colors: {
    primaryColor: '#800000',
    secondaryColor: '#3498DB',
  },

  copyright: `Copyright © ${new Date().getFullYear()} Alexandre Archambault and almond contributors`,

  highlight: {
    theme: 'default',
  },

  scripts: ['https://buttons.github.io/buttons.js'],

  onPageNav: 'separate',
  cleanUrl: true,

  enableUpdateTime: true, // doesn't seem to work

  editUrl: 'https://github.com/almond-sh/almond/edit/master/doc/docs/',
};

module.exports = siteConfig;
