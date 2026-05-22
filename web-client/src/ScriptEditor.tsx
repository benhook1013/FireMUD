import { useState } from 'react';
import { Box, TextField, Button } from '@mui/material';
import { useRunScriptMutation } from './api/firemudApi';

export default function ScriptEditor() {
  const [name, setName] = useState('');
  const [definition, setDefinition] = useState('');

  const runScript = useRunScriptMutation();

  const handleTest = async () => {
    try {
      const result = await runScript.mutateAsync({ name, definition });
      alert(result.output);
    } catch (err) {
      alert('Failed to run script');
      console.error(err);
    }
  };

  return (
    <Box sx={{ p: 2 }}>
      <h2>Script Editor</h2>
      <TextField
        label="Script Name"
        value={name}
        onChange={(e) => setName(e.target.value)}
        margin="normal"
        fullWidth
      />
      <TextField
        label="Script Definition"
        value={definition}
        onChange={(e) => setDefinition(e.target.value)}
        margin="normal"
        multiline
        minRows={4}
        fullWidth
      />
      <Button
        variant="contained"
        sx={{ mt: 1 }}
        onClick={handleTest}
        disabled={runScript.isPending}
      >
        {runScript.isPending ? 'Running...' : 'Test Run'}
      </Button>
    </Box>
  );
}
