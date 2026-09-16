package main

import (
	"log"
	"runtime"
)

func main() {
	log.Printf(
		"ZeroYAML Runner starting - Go %s - %s/%s",
		runtime.Version(),
		runtime.GOOS,
		runtime.GOARCH,
	)
}
