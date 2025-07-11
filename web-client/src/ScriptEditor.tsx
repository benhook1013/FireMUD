import { useState } from 'react';
import { Box, TextField, Button } from '@mui/material';

export default function ScriptEditor() {
  const [name, setName] = useState('');
  const [definition, setDefinition] = useState('');

  const handleTest = () => {
    // In a real implementation this would call the Automation Scripting Service
    // via REST or gRPC. For now we simply display the payload.
    alert(`Test running script "${name}" with definition:\n${definition}`);
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
      <Button variant="contained" sx={{ mt: 1 }} onClick={handleTest}>
        Test Run
      </Button>
    </Box>
  );
}
