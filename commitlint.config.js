module.exports = {
  extends: [
    '@commitlint/config-conventional'
  ],
  rules: {
    'scope-case': [0],
    'scope-enum': [
      2,
      'always',
      [
        'CoughExtractor',
        'ML'
      ],
    ],
    'header-max-length': [1, "always", 140]
  },
};
