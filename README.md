# dots

This is a fork from bhauman's dotster. Dotster is a game implemented in ClojureScript using the Core.async library.

[See the blog post explaining the game](http://rigsomelight.com/2013/08/12/clojurescript-core-async-dots-game.html)


## The Improvement

This fork contains bug fixes and major code refactors that helps beginner to understand core.async patterns.

  1. use the latest core.async and clojurescript to make code work
  2. refactor chan related code into chan module and use go-loop macro.
  3. refactor render related code into render module.
  4. misc

To compile:

  lein cljsbuild once
  open resources/public/dots.html


## Core.async Philosophy and Pattern

Rich gave a talk of core.async at InfoQ SF 2013. Following are some philosophies and patterns based on my understanding.


1. function calls make poor machine.

Function call chains are tight coupling. You can not split function calls across web workers. In distributed system, we use rabbitmq or alike to scale up. Here we use CSP style channel to connect in process components.

2. Event callback force bad intimacy, and is Inverse Of Control. 

Event callback is messy because your logic is scattered around inside different event handlers. It is IOC because you have no control when your event handler got called.

Event callback forces intimacy due to tight coupling. Though promise helps lessen the problem. Callback still hard to reason about and is IOC.

3. Friends don't let friends put logic in event handler.

Event handler should be don't do too much event handler.

Event handlers read events from input chan, simple filter or dispatch based on predicates, and put the event data into output channel. 
After storing event into output channel, event handler returns the output channel. The next stage event handlers will do the same. This eventually creates a chain that connects all components through channels.

4. Data Based Communication 

With channel, you basic communication is not calling anymore, it is data now !!! 

We can split the chain and distribute the work across webworkers. Think about it, with today's event callback, you can not even call across webworkers. With channel, you connect components through data, which is easy to distribute the work, and easy to reason about.



## License

Copyright © 2013

Distributed under the Eclipse Public License, the same as Clojure.
