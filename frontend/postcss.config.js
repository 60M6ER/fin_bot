const plugins = []

if (process.env.ENABLE_AUTOPREFIXER === 'true') {
  const { default: autoprefixer } = await import('autoprefixer')

  plugins.push(autoprefixer({
    overrideBrowserslist: [
      'last 4 Chrome versions',
      'last 4 Firefox versions',
      'last 4 Edge versions',
      'last 4 Safari versions',
      'last 4 Android versions',
      'last 4 ChromeAndroid versions',
      'last 4 FirefoxAndroid versions',
      'last 4 iOS versions'
    ]
  }))
}

export default {
  plugins
}
