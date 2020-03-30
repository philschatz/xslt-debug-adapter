const path = require('path')

const xslPath = path.join(__dirname, 'test', 'cnxml-to-html5.xsl')
const sourcePath = path.join(__dirname, 'test', 'all.cnxml')
const destinationPath = path.join(__dirname, 'test', 'output.xhtml')

const breakpointLines = [
  55,
  222
]

const interestingStackFrame = 5
const interestingVariablesId = 6


// https://github.com/microsoft/vscode-debugadapter-node/blob/master/protocol/src/debugProtocol.ts
const COMMANDS = [
  { command: 'initialize', 
    arguments: {
      adapterID: '123',
      linesStartAt1: true,
      columnsStartAt1: true,
    }
  },

  { command: 'configurationDone' },

  { command: 'setBreakpoints',
    arguments: {
      source: {
        name: path.basename(xslPath),
        path: xslPath
      },
      breakpoints: breakpointLines.map(l => ({ line: l}))
    }
  },

  2,

  { command: 'launch',
    arguments: {
      // noDebug: false,
      xslPath,
      sourcePath,
      destinationPath
    }
  },

  5,

  { command: 'stackTrace',
    arguments: {
      threadId: 1
    }
  },

  3,

  { command: 'scopes',
    arguments: {
      frameId: interestingStackFrame
    }
  },

  3,

  { command: 'variables',
    arguments: {
      variablesReference: interestingVariablesId
    }
  },

  { command: 'continue',
    arguments: {
      threadId: 1
    }
  },

]


const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))


const run = async () => {
  // console.error('Starting up')

  for (const cmd of COMMANDS) {

    if (typeof cmd === 'number') {
      await sleep(cmd * 1000)
    } else {
      const str = JSON.stringify({type: 'request', ...cmd});
      console.log(`Content-Length: ${str.length + 1}\n\n${str}`)
  
      await sleep(1000)
  
    }
  }

  // console.error('Shutting down')
}
run().then(null, (err) => console.log(err))
