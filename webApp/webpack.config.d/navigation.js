// Assets and lazy JS/Wasm chunks share the origin root, including cold deep links.
config.output = { ...config.output, publicPath: "/" };
config.devServer = {
    ...config.devServer,
    historyApiFallback: { index: "/index.html", disableDotRule: true },
};
